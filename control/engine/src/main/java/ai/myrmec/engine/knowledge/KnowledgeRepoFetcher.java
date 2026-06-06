package ai.myrmec.engine.knowledge;

import ai.myrmec.engine.project.ProjectKnowledgeRepo;
import ai.myrmec.engine.secret.CredentialType;
import ai.myrmec.engine.secret.SecretPayload;
import ai.myrmec.engine.secret.SecretResolverService;
import ai.myrmec.engine.secret.SecretTypeMismatchException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches knowledge documents from project-attached git repositories on demand.
 *
 * <p>Replaces the previous DB-sync model: knowledge repo contents are cloned,
 * parsed, and returned as transient {@link KnowledgeDocument} instances each
 * time {@link #fetch(UUID, ProjectKnowledgeRepo)} is called. Results are
 * cached briefly in memory and the underlying git working tree is kept in a
 * persistent cache directory and refreshed via {@code git pull} on cache miss.
 *
 * <p>Failure policy: parse/fetch errors are logged and an empty list is
 * returned. Callers must never propagate a fetch failure into task dispatch.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeRepoFetcher {

    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final List<String> DEFAULT_INSTRUCTION_PATHS =
            List.of("docs/**/*.md", ".github/instructions/**/*.md");

    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---\\s*\\n", Pattern.DOTALL);
    private static final Pattern APPLY_TO_PATTERN = Pattern.compile(
            "applyTo:\\s*[\"']?([^\"'\\n]+)[\"']?");
    private static final Pattern DESCRIPTION_PATTERN = Pattern.compile(
            "description:\\s*[\"']?([^\"'\\n]+)[\"']?");

    private final SecretResolverService secretResolver;

    @Value("${myrmec.knowledge.cache-dir:}")
    private String configuredCacheDir;

    private Path cacheRoot;

    private final Map<String, CacheEntry> resultCache = new ConcurrentHashMap<>();
    private final Map<String, Object> repoLocks = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        try {
            Path base = (configuredCacheDir != null && !configuredCacheDir.isBlank())
                    ? Path.of(configuredCacheDir)
                    : Path.of(System.getProperty("java.io.tmpdir"), "myrmec-knowledge-cache");
            Files.createDirectories(base);
            this.cacheRoot = base;
            log.info("KnowledgeRepoFetcher cache directory: {}", base);
        } catch (IOException e) {
            log.error("Failed to initialise knowledge cache dir, falling back to per-call temp clones: {}",
                    e.getMessage());
            this.cacheRoot = null;
        }
    }

    /**
     * Fetch knowledge documents from a single project knowledge repo.
     *
     * @return transient (unsaved) {@link KnowledgeDocument} instances suitable
     *         for merging into a task context. Never throws; returns empty on
     *         any error.
     */
    public List<KnowledgeDocument> fetch(UUID projectId, ProjectKnowledgeRepo repo) {
        if (repo == null || repo.getRepoUrl() == null || repo.getRepoUrl().isBlank()) {
            return List.of();
        }

        String cacheKey = buildCacheKey(repo);
        CacheEntry cached = resultCache.get(cacheKey);
        if (cached != null && cached.expiresAt.isAfter(Instant.now())) {
            log.debug("Knowledge repo cache hit for {}", repo.getName());
            return cached.documents;
        }

        Object lock = repoLocks.computeIfAbsent(cacheKey, k -> new Object());
        synchronized (lock) {
            // Re-check after acquiring the lock
            cached = resultCache.get(cacheKey);
            if (cached != null && cached.expiresAt.isAfter(Instant.now())) {
                return cached.documents;
            }

            List<KnowledgeDocument> documents = fetchFresh(projectId, repo, cacheKey);
            resultCache.put(cacheKey, new CacheEntry(documents, Instant.now().plus(CACHE_TTL)));
            return documents;
        }
    }

    private List<KnowledgeDocument> fetchFresh(UUID projectId, ProjectKnowledgeRepo repo, String cacheKey) {
        Path workDir = null;
        boolean usingPersistentCache = cacheRoot != null;
        try {
            UsernamePasswordCredentialsProvider creds = resolveGitCredentials(projectId, repo);

            if (usingPersistentCache) {
                workDir = cacheRoot.resolve(cacheKey);
                syncPersistentClone(workDir, repo, creds);
            } else {
                workDir = Files.createTempDirectory("myrmec-knowledge-");
                shallowClone(workDir, repo, creds);
            }

            List<String> globPatterns = (repo.getInstructionPaths() == null || repo.getInstructionPaths().isEmpty())
                    ? DEFAULT_INSTRUCTION_PATHS
                    : repo.getInstructionPaths();

            Set<Path> matched = findMatchingFiles(workDir, globPatterns);
            log.debug("Knowledge repo '{}' matched {} files", repo.getName(), matched.size());

            List<KnowledgeDocument> docs = new ArrayList<>(matched.size());
            for (Path file : matched) {
                try {
                    docs.add(parseFile(workDir, file, projectId, repo));
                } catch (Exception e) {
                    log.warn("Failed to parse knowledge file {} from repo {}: {}",
                            file, repo.getName(), e.getMessage());
                }
            }
            return List.copyOf(docs);
        } catch (Exception e) {
            log.warn("Failed to fetch knowledge repo '{}' ({}): {}",
                    repo.getName(), repo.getRepoUrl(), e.getMessage());
            return List.of();
        } finally {
            if (!usingPersistentCache && workDir != null) {
                cleanupTempDir(workDir);
            }
        }
    }

    private void syncPersistentClone(Path dir, ProjectKnowledgeRepo repo, UsernamePasswordCredentialsProvider creds) throws GitAPIException, IOException {
        Path gitDir = dir.resolve(".git");
        if (Files.isDirectory(gitDir)) {
            // Refresh existing checkout
            try (Git git = Git.open(dir.toFile())) {
                git.fetch()
                        .setCredentialsProvider(creds)
                        .setRemoveDeletedRefs(true)
                        .call();
                git.checkout().setName(repo.getBranch()).setForced(true).call();
                git.pull().setCredentialsProvider(creds).call();
            } catch (Exception e) {
                log.warn("Persistent clone refresh failed for {}; re-cloning. Reason: {}",
                        repo.getRepoUrl(), e.getMessage());
                deleteRecursively(dir);
                shallowClone(dir, repo, creds);
            }
        } else {
            if (Files.exists(dir)) {
                deleteRecursively(dir);
            }
            shallowClone(dir, repo, creds);
        }
    }

    private void shallowClone(Path dir, ProjectKnowledgeRepo repo, UsernamePasswordCredentialsProvider creds) throws GitAPIException, IOException {
        Files.createDirectories(dir);
        Git.cloneRepository()
                .setURI(repo.getRepoUrl())
                .setDirectory(dir.toFile())
                .setBranch(repo.getBranch() != null ? repo.getBranch() : "main")
                .setDepth(1)
                .setCredentialsProvider(creds)
                .call()
                .close();
    }

    /**
     * Resolve git credentials from the repo's credential secret reference.
     * Accepts BEARER_TOKEN (sent as username + empty password — GitHub/GitLab
     * PAT convention) and USERNAME_PASSWORD. SSL_PRIVATE_KEY is logged as a
     * known gap (JGit SSH transport wiring not yet implemented).
     */
    private UsernamePasswordCredentialsProvider resolveGitCredentials(UUID projectId, ProjectKnowledgeRepo repo) {
        UUID secretId = repo.getCredentialSecretId();
        if (secretId == null) {
            return null;
        }
        try {
            SecretPayload payload = secretResolver.resolveOneOf(secretId, projectId,
                    CredentialType.BEARER_TOKEN,
                    CredentialType.USERNAME_PASSWORD,
                    CredentialType.SSL_PRIVATE_KEY);
            return switch (payload) {
                case SecretPayload.BearerToken b -> new UsernamePasswordCredentialsProvider(b.token(), "");
                case SecretPayload.UsernamePassword up ->
                        new UsernamePasswordCredentialsProvider(up.username(), up.password());
                case SecretPayload.SslPrivateKey ignored -> {
                    log.warn("SSL_PRIVATE_KEY secret {} cannot be used over HTTPS clone (SSH transport not wired); skipping auth for repo {}",
                            secretId, repo.getName());
                    yield null;
                }
                default -> null;
            };
        } catch (SecretTypeMismatchException e) {
            log.warn("Knowledge repo {} credential secret {} has unsupported type: {}",
                    repo.getName(), secretId, e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("Failed to resolve credentials for knowledge repo {}: {}", repo.getName(), e.getMessage());
            return null;
        }
    }

    private KnowledgeDocument parseFile(Path repoRoot, Path file, UUID projectId, ProjectKnowledgeRepo repo)
            throws IOException {
        String relativePath = repoRoot.relativize(file).toString().replace('\\', '/');
        String content = Files.readString(file, StandardCharsets.UTF_8);

        String description = null;
        List<String> appliesTo = null;

        Matcher fmMatcher = FRONTMATTER_PATTERN.matcher(content);
        if (fmMatcher.find()) {
            String fm = fmMatcher.group(1);

            Matcher descMatcher = DESCRIPTION_PATTERN.matcher(fm);
            if (descMatcher.find()) {
                description = descMatcher.group(1).trim();
            }
            Matcher applyMatcher = APPLY_TO_PATTERN.matcher(fm);
            if (applyMatcher.find()) {
                String value = applyMatcher.group(1).trim();
                appliesTo = Arrays.stream(value.split("\\s*,\\s*"))
                        .filter(s -> !s.isBlank())
                        .toList();
            }
        }

        KnowledgeCategory category = inferCategory(relativePath, content);
        String name = generateDocumentName(relativePath, description);
        int priority = inferPriority(category, relativePath);

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setScope(KnowledgeScope.PROJECT);
        doc.setProjectId(projectId);
        doc.setCategory(category);
        doc.setName(name);
        doc.setContent(content);
        doc.setPriority(priority);
        doc.setAppliesTo(appliesTo);
        doc.setSourcePath("repo:" + repo.getName() + ":" + relativePath);
        doc.setActive(true);
        return doc;
    }

    private Set<Path> findMatchingFiles(Path root, List<String> globPatterns) throws IOException {
        Set<Path> matched = new HashSet<>();
        FileSystem fs = root.getFileSystem();
        for (String pattern : globPatterns) {
            PathMatcher matcher = fs.getPathMatcher("glob:" + pattern);
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path rel = root.relativize(file);
                    if (matcher.matches(rel)) {
                        matched.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.getFileName() != null && dir.getFileName().toString().equals(".git")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return matched;
    }

    private KnowledgeCategory inferCategory(String path, String content) {
        String lp = path.toLowerCase();
        String lc = content.toLowerCase();
        if (lp.contains("instruction") || lp.contains(".instructions.md")) {
            return KnowledgeCategory.INSTRUCTION;
        }
        if (lp.contains("standard") || lp.contains("convention") || lp.contains("style")) {
            return KnowledgeCategory.STANDARD;
        }
        if (lp.contains("requirement") || lp.contains("spec")) {
            return KnowledgeCategory.REQUIREMENT;
        }
        if (lp.contains("architecture") || lp.contains("design") || lp.contains("erd")
                || lc.contains("## architecture") || lc.contains("# architecture")) {
            return KnowledgeCategory.ARCHITECTURE;
        }
        return KnowledgeCategory.INSTRUCTION;
    }

    private String generateDocumentName(String path, String description) {
        if (description != null && !description.isBlank()) {
            return description.length() > 100 ? description.substring(0, 97) + "..." : description;
        }
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        if (fileName.endsWith(".md")) {
            fileName = fileName.substring(0, fileName.length() - 3);
        }
        return Arrays.stream(fileName.split("[-_.]"))
                .filter(s -> !s.isBlank())
                .map(s -> Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase())
                .reduce((a, b) -> a + " " + b)
                .orElse(fileName);
    }

    private int inferPriority(KnowledgeCategory category, String path) {
        int base = switch (category) {
            case STANDARD -> 100;
            case REQUIREMENT -> 90;
            case ARCHITECTURE -> 80;
            case INSTRUCTION -> 70;
        };
        long depth = path.chars().filter(c -> c == '/').count();
        return base + Math.max(0, 10 - (int) depth * 2);
    }

    private String buildCacheKey(ProjectKnowledgeRepo repo) {
        String raw = repo.getRepoUrl() + "|" + (repo.getBranch() == null ? "main" : repo.getBranch())
                + "|" + (repo.getCredentialSecretId() == null ? "" : repo.getCredentialSecretId());
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            // Use first 16 chars — collision risk for cache key is negligible
            return sb.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private void cleanupTempDir(Path dir) {
        try {
            deleteRecursively(dir);
        } catch (IOException e) {
            log.warn("Failed to cleanup temp directory {}: {}", dir, e.getMessage());
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                try {
                    Files.delete(file);
                } catch (IOException e) {
                    // On Windows, git pack files may be read-only
                    file.toFile().setWritable(true);
                    Files.delete(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private record CacheEntry(List<KnowledgeDocument> documents, Instant expiresAt) {}
}
