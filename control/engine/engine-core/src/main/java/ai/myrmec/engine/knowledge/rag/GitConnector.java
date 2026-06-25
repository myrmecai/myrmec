package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine.spi.connector.ConnectorContext;
import ai.myrmec.engine.spi.connector.ConnectorException;
import ai.myrmec.engine.spi.connector.EmittedChunk;
import ai.myrmec.engine.spi.connector.KnowledgeSourceConnector;
import ai.myrmec.engine.spi.connector.SourceLocator;
import ai.myrmec.engine.spi.connector.SyncResult;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.util.FileUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@link KnowledgeSourceConnector} (type = {@code "git"}) that shallow-clones a
 * git repository over HTTPS and emits one chunk per text file (#21).
 *
 * <p>The source {@code uri} is the repository URL, optionally carrying the
 * branch as a fragment (e.g. {@code https://github.com/acme/api.git#main}).
 * Authentication uses a personal-access token: the connector first asks the
 * {@link ConnectorContext} secret resolver for {@code tokenSecret}, and falls
 * back to an inline {@code token} in the source config. Public repos need
 * neither.</p>
 *
 * <p><strong>Config JSON</strong> (all optional) read from
 * {@code knowledge_sources.config_json}:</p>
 * <pre>{@code
 * {
 *   "branch": "main",                 // overrides the uri fragment
 *   "subdirectory": "docs",           // only index paths under here
 *   "includeGlobs": ["**\/*.md"],     // if set, only matching paths are indexed
 *   "excludeGlobs": ["**\/vendor/**"],// always skipped (in addition to defaults)
 *   "maxFileSizeBytes": 1048576,      // skip files larger than this (default 1 MiB)
 *   "tokenSecret": "github-pat",      // secret token resolved via ConnectorContext
 *   "token": "ghp_xxx"                // inline PAT fallback (dev / public mirrors)
 * }
 * }</pre>
 *
 * <p>Chunking is file-per-chunk: locator = repo-relative path, content = the
 * file's UTF-8 text. Binary files (NUL byte in the first bytes), blank files,
 * and oversized files are skipped; unreadable files are recorded in
 * {@link SyncResult#errors()} (which downgrades the run to
 * {@link SyncResult.Status#PARTIAL}). A clone/auth failure is non-recoverable
 * and throws {@link ConnectorException}.</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GitConnector implements KnowledgeSourceConnector {

    public static final String CONNECTOR_TYPE = "git";

    /** Default per-file size ceiling (1 MiB) when not overridden in config. */
    private static final long DEFAULT_MAX_FILE_SIZE_BYTES = 1_048_576L;

    /** Shallow-clone depth — we only need the tip to index current content. */
    private static final int CLONE_DEPTH = 1;

    /** Bytes sampled from the head of a file to detect binary content. */
    private static final int BINARY_SNIFF_BYTES = 8_000;

    private final ObjectMapper objectMapper;

    @Override
    public String type() {
        return CONNECTOR_TYPE;
    }

    @Override
    public SyncResult sync(SourceLocator locator, ConnectorContext context) throws ConnectorException {
        Instant startedAt = Instant.now();
        GitSourceConfig config = parseConfig(locator.config().get("raw"));

        String[] uriAndFragment = splitUriFragment(locator.uri());
        String repoUrl = uriAndFragment[0];
        String branch = firstNonBlank(config.branch(), uriAndFragment[1]);
        String token = resolveToken(config, context);
        long maxFileSize = config.maxFileSizeBytes() != null && config.maxFileSizeBytes() > 0
                ? config.maxFileSizeBytes()
                : DEFAULT_MAX_FILE_SIZE_BYTES;

        Path workDir = createTempDir();
        List<String> errors = new ArrayList<>();
        long emitted = 0;
        try (Git git = cloneRepo(repoUrl, branch, token, workDir)) {
            String commit = resolveHeadCommit(git);
            emitted = walkAndEmit(workDir, config, maxFileSize, branch, commit, context, errors);
            log.info("Git sync emitted {} chunk(s) from {} (branch={}, commit={}, {} error(s))",
                    emitted, repoUrl, branch == null ? "<default>" : branch, commit, errors.size());
        } catch (GitAPIException e) {
            throw new ConnectorException("Git clone failed for " + repoUrl
                    + (branch == null ? "" : " (branch " + branch + ")") + ": " + e.getMessage(), e);
        } finally {
            deleteRecursively(workDir);
        }

        SyncResult.Status status = errors.isEmpty() ? SyncResult.Status.SUCCESS : SyncResult.Status.PARTIAL;
        Instant completedAt = Instant.now();
        return new SyncResult(status, emitted, errors, Duration.between(startedAt, completedAt), completedAt);
    }

    // --- clone ---------------------------------------------------------------

    private Git cloneRepo(String repoUrl, String branch, String token, Path workDir) throws GitAPIException {
        CloneCommand clone = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(workDir.toFile())
                .setCloneAllBranches(false);
        // Shallow clone is a remote-transport optimisation; the local (file://)
        // transport does not support it, so only request depth for remote URLs.
        if (!isLocalUri(repoUrl)) {
            clone.setDepth(CLONE_DEPTH);
        }
        if (branch != null) {
            clone.setBranch(branch);
            clone.setBranchesToClone(List.of(Constants.R_HEADS + branch));
        }
        if (token != null) {
            // GitHub/GitLab accept the PAT as the username with an empty password.
            clone.setCredentialsProvider(new UsernamePasswordCredentialsProvider(token, ""));
        }
        return clone.call();
    }

    private static boolean isLocalUri(String uri) {
        String lower = uri.toLowerCase();
        return lower.startsWith("file:") || !lower.contains("://");
    }

    private String resolveHeadCommit(Git git) {
        try {
            ObjectId head = git.getRepository().resolve(Constants.HEAD);
            return head == null ? null : head.abbreviate(8).name();
        } catch (IOException e) {
            log.debug("Could not resolve HEAD commit: {}", e.getMessage());
            return null;
        }
    }

    // --- walk + emit ---------------------------------------------------------

    private long walkAndEmit(Path workDir, GitSourceConfig config, long maxFileSize,
                             String branch, String commit, ConnectorContext context,
                             List<String> errors) {
        Path root = config.subdirectory() != null && !config.subdirectory().isBlank()
                ? workDir.resolve(config.subdirectory())
                : workDir;
        if (!Files.isDirectory(root)) {
            errors.add("subdirectory not found: " + config.subdirectory());
            return 0;
        }

        List<PathMatcher> includes = compileGlobs(config.includeGlobs());
        List<PathMatcher> excludes = compileGlobs(config.excludeGlobs());

        long emitted = 0;
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> candidates = files.filter(Files::isRegularFile).toList();
            for (Path file : candidates) {
                Path relative = workDir.relativize(file);
                String relPath = relative.toString().replace('\\', '/');
                if (isUnderGitDir(relPath)) {
                    continue;
                }
                if (!matchesFilters(relative, includes, excludes)) {
                    continue;
                }
                EmittedChunk chunk = readChunk(file, relPath, maxFileSize, branch, commit, errors);
                if (chunk != null) {
                    context.chunkSink().accept(chunk);
                    emitted++;
                }
            }
        } catch (IOException e) {
            errors.add("walk failed under " + root + ": " + e.getMessage());
        }
        return emitted;
    }

    private EmittedChunk readChunk(Path file, String relPath, long maxFileSize,
                                   String branch, String commit, List<String> errors) {
        try {
            long size = Files.size(file);
            if (size > maxFileSize) {
                errors.add("skipped (too large, " + size + " bytes): " + relPath);
                return null;
            }
            byte[] bytes = Files.readAllBytes(file);
            if (isBinary(bytes)) {
                return null; // binary assets are not retrievable text; skip silently
            }
            String content = new String(bytes, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                return null;
            }
            return new EmittedChunk(relPath, content, chunkMetadata(relPath, branch, commit));
        } catch (IOException e) {
            errors.add("skipped (read error): " + relPath + " — " + e.getMessage());
            return null;
        }
    }

    private Map<String, String> chunkMetadata(String relPath, String branch, String commit) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("path", relPath);
        int dot = relPath.lastIndexOf('.');
        int slash = relPath.lastIndexOf('/');
        if (dot > slash && dot < relPath.length() - 1) {
            metadata.put("ext", relPath.substring(dot + 1));
        }
        if (branch != null) {
            metadata.put("branch", branch);
        }
        if (commit != null) {
            metadata.put("commit", commit);
        }
        return metadata;
    }

    // --- helpers -------------------------------------------------------------

    private GitSourceConfig parseConfig(String rawJson) throws ConnectorException {
        if (rawJson == null || rawJson.isBlank()) {
            return GitSourceConfig.EMPTY;
        }
        try {
            return objectMapper.readValue(rawJson, GitSourceConfig.class);
        } catch (IOException e) {
            throw new ConnectorException("Invalid git source config JSON: " + e.getMessage(), e);
        }
    }

    private String resolveToken(GitSourceConfig config, ConnectorContext context) {
        if (config.tokenSecret() != null && !config.tokenSecret().isBlank()) {
            String resolved = context.resolveSecret(config.tokenSecret());
            if (resolved != null && !resolved.isBlank()) {
                return resolved;
            }
        }
        return firstNonBlank(config.token(), null);
    }

    private static String[] splitUriFragment(String uri) {
        int hash = uri.indexOf('#');
        if (hash < 0) {
            return new String[]{uri, null};
        }
        String fragment = uri.substring(hash + 1);
        return new String[]{uri.substring(0, hash), fragment.isBlank() ? null : fragment};
    }

    private static boolean matchesFilters(Path relative, List<PathMatcher> includes, List<PathMatcher> excludes) {
        for (PathMatcher exclude : excludes) {
            if (exclude.matches(relative)) {
                return false;
            }
        }
        if (includes.isEmpty()) {
            return true;
        }
        for (PathMatcher include : includes) {
            if (include.matches(relative)) {
                return true;
            }
        }
        return false;
    }

    private static List<PathMatcher> compileGlobs(List<String> globs) {
        if (globs == null || globs.isEmpty()) {
            return List.of();
        }
        List<PathMatcher> matchers = new ArrayList<>(globs.size());
        for (String glob : globs) {
            if (glob == null || glob.isBlank()) {
                continue;
            }
            matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + glob));
            // Java's "**" does not match zero directories, so "**/*.md" misses a
            // top-level README.md. Add a root-level equivalent so the intuitive
            // "all markdown anywhere" reading holds.
            if (glob.startsWith("**/")) {
                String rootEquivalent = glob.substring(3);
                if (!rootEquivalent.isBlank()) {
                    matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + rootEquivalent));
                }
            }
        }
        return matchers;
    }

    private static boolean isBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, BINARY_SNIFF_BYTES);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUnderGitDir(String relPath) {
        return relPath.equals(".git") || relPath.startsWith(".git/");
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b != null && !b.isBlank() ? b : null;
    }

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("myrmec-git-sync-");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create temp dir for git sync", e);
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try {
            // RETRY tolerates transient Windows locks on memory-mapped pack files;
            // IGNORE_ERRORS keeps cleanup best-effort so it never fails a sync.
            FileUtils.delete(dir.toFile(),
                    FileUtils.RECURSIVE | FileUtils.RETRY | FileUtils.IGNORE_ERRORS);
        } catch (IOException e) {
            log.debug("Git-sync cleanup failed for {}: {}", dir, e.getMessage());
        }
    }

    /**
     * Connector-owned config schema deserialised from
     * {@code knowledge_sources.config_json}. Unknown keys are ignored so the
     * schema can grow without breaking older sources.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record GitSourceConfig(
            String branch,
            String subdirectory,
            List<String> includeGlobs,
            List<String> excludeGlobs,
            Long maxFileSizeBytes,
            String tokenSecret,
            String token) {

        static final GitSourceConfig EMPTY =
                new GitSourceConfig(null, null, null, null, null, null, null);
    }
}
