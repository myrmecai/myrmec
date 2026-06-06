package ai.myrmec.engine.knowledge;

import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectKnowledgeRepo;
import ai.myrmec.engine.project.ProjectKnowledgeRepoRepository;
import ai.myrmec.engine.project.ProjectService;
import ai.myrmec.engine.secret.CredentialType;
import ai.myrmec.engine.secret.SecretPayload;
import ai.myrmec.engine.secret.SecretResolverService;
import ai.myrmec.engine.secret.SecretTypeMismatchException;
import ai.myrmec.engine.websocket.message.payload.TaskContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.*;

/**
 * Service for resolving task execution context.
 * 
 * Compiles organization and project knowledge documents into a TaskContext
 * that gets injected into TaskAssignPayload for agent consumption.
 * 
 * Resolution strategy:
 * 1. Load all active org-level + project-level knowledge documents
 * 2. Filter by appliesTo patterns if step path is provided
 * 3. Sort by priority (highest first)
 * 4. Include documents until token budget is exhausted
 * 5. Add RAG config if project has it configured
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaskContextResolver {

    private final KnowledgeDocumentService knowledgeDocumentService;
    private final ProjectService projectService;
    private final ProjectKnowledgeRepoRepository projectKnowledgeRepoRepository;
    private final KnowledgeRepoFetcher knowledgeRepoFetcher;
    private final SecretResolverService secretResolver;

    /**
     * Default character budget for embedded knowledge.
     * Roughly 8000 tokens at ~4 chars/token = 32000 chars.
     */
    private static final int DEFAULT_CHAR_BUDGET = 32000;

    /**
     * Resolve task context for a project.
     *
     * @param projectId Project ID to resolve context for
     * @return Resolved TaskContext with knowledge and RAG config
     */
    public TaskContext resolve(UUID projectId) {
        return resolve(projectId, null, null, DEFAULT_CHAR_BUDGET);
    }

    /**
     * Resolve task context for a project with step-specific filtering.
     *
     * @param projectId Project ID
     * @param stepPath Optional step/file path for appliesTo filtering
     * @return Resolved TaskContext
     */
    public TaskContext resolve(UUID projectId, String stepPath) {
        return resolve(projectId, stepPath, null, DEFAULT_CHAR_BUDGET);
    }

    /**
     * Resolve task context with workflow artifacts repo override.
     * 
     * @param projectId Project ID
     * @param stepPath Optional step/file path for appliesTo filtering
     * @param artifactsRepo Workflow-level artifacts repo config (overrides project repo)
     * @return Resolved TaskContext
     */
    public TaskContext resolve(UUID projectId, String stepPath, Map<String, Object> artifactsRepo) {
        return resolve(projectId, stepPath, artifactsRepo, DEFAULT_CHAR_BUDGET);
    }

    /**
     * Resolve task context with full control.
     *
     * @param projectId Project ID
     * @param stepPath Optional step/file path for appliesTo filtering
     * @param artifactsRepo Workflow-level artifacts repo config (overrides project repo)
     * @param charBudget Maximum characters for embedded knowledge
     * @return Resolved TaskContext
     */
    public TaskContext resolve(UUID projectId, String stepPath, Map<String, Object> artifactsRepo, int charBudget) {
        log.debug("Resolving task context for project {} (stepPath: {}, budget: {})",
                projectId, stepPath, charBudget);

        // Get all relevant knowledge documents (org + project, persisted)
        List<KnowledgeDocument> allDocs = new ArrayList<>(
                knowledgeDocumentService.findForContextResolution(projectId));
        log.debug("Loaded {} persisted knowledge documents", allDocs.size());

        // Merge in fetched documents from any attached knowledge repos
        List<ProjectKnowledgeRepo> knowledgeRepos =
                projectKnowledgeRepoRepository.findByProjectIdOrderByNameAsc(projectId);
        for (ProjectKnowledgeRepo repo : knowledgeRepos) {
            List<KnowledgeDocument> fetched = knowledgeRepoFetcher.fetch(projectId, repo);
            log.debug("Knowledge repo '{}' contributed {} documents", repo.getName(), fetched.size());
            allDocs.addAll(fetched);
        }

        // Re-sort merged docs by priority DESC so highest-priority sources win the budget
        allDocs.sort(Comparator.comparingInt(KnowledgeDocument::getPriority).reversed());

        // Filter by appliesTo patterns if step path provided
        List<KnowledgeDocument> filteredDocs;
        if (stepPath != null && !stepPath.isBlank()) {
            filteredDocs = filterByAppliesTo(allDocs, stepPath);
            log.debug("After appliesTo filtering: {} documents", filteredDocs.size());
        } else {
            // Include only docs without appliesTo restrictions (universal ones)
            filteredDocs = allDocs.stream()
                    .filter(doc -> doc.getAppliesTo() == null || doc.getAppliesTo().isEmpty())
                    .toList();
            log.debug("No stepPath, using {} universal documents", filteredDocs.size());
        }

        // Compile knowledge entries with budget
        List<TaskContext.KnowledgeEntry> entries = new ArrayList<>();
        int totalChars = 0;

        for (KnowledgeDocument doc : filteredDocs) {
            int contentLength = doc.getContent() != null ? doc.getContent().length() : 0;

            // Stop if we'd exceed budget
            if (totalChars + contentLength > charBudget) {
                log.debug("Budget exhausted at {} chars, stopping. Skipped doc: {}", totalChars, doc.getName());
                break;
            }

            entries.add(TaskContext.KnowledgeEntry.builder()
                    .category(doc.getCategory().name())
                    .name(doc.getName())
                    .content(doc.getContent())
                    .priority(doc.getPriority())
                    .build());

            totalChars += contentLength;
        }

        log.debug("Compiled {} knowledge entries ({} chars)", entries.size(), totalChars);

        // Get RAG config from project
        TaskContext.RagConfig ragConfig = null;
        Project project = projectService.findById(projectId);
        if (project.getRagConfig() != null && !project.getRagConfig().isEmpty()) {
            ragConfig = buildRagConfig(project.getRagConfig());
            log.debug("RAG config included from project");
        }
        
        // Build workspace config - prefer workflow artifactsRepo, fallback to project repo
        TaskContext.WorkspaceConfig workspaceConfig = resolveWorkspaceConfig(project, artifactsRepo);

        return TaskContext.builder()
                .knowledge(entries)
                .rag(ragConfig)
                .knowledgeCharCount(totalChars)
                .workspace(workspaceConfig)
                .build();
    }

    /**
     * Resolve workspace configuration.
     * Priority: workflow.artifactsRepo > project.workspaceRepoUrl
     */
    private TaskContext.WorkspaceConfig resolveWorkspaceConfig(Project project, Map<String, Object> artifactsRepo) {
        // Use workflow artifacts repo if configured
        if (artifactsRepo != null && !artifactsRepo.isEmpty()) {
            String url = getString(artifactsRepo, "url");
            if (url != null && !url.isBlank()) {
                String branch = getString(artifactsRepo, "baseBranch");
                if (branch == null || branch.isBlank()) {
                    branch = "main";
                }
                
                // Lookup credential by secret id if specified
                UUID credentialSecretId = getUuid(artifactsRepo, "credentialSecretId");
                String repoToken = resolveGitTokenBySecretId(project.getId(), credentialSecretId);
                
                log.debug("Workspace config from workflow: repo={}, branch={}, hasToken={}", 
                        url, branch, repoToken != null);
                
                return TaskContext.WorkspaceConfig.builder()
                        .repoUrl(url)
                        .branch(branch)
                        .repoToken(repoToken)
                        .build();
            }
        }
        
        // Fallback to project default workspace repo settings
        if (project.getWorkspaceRepoUrl() != null && !project.getWorkspaceRepoUrl().isBlank()) {
            String repoToken = resolveGitTokenBySecretId(project.getId(), project.getWorkspaceCredentialSecretId());
            
            TaskContext.WorkspaceConfig config = TaskContext.WorkspaceConfig.builder()
                    .repoUrl(project.getWorkspaceRepoUrl())
                    .branch(project.getWorkspaceRepoBranch() != null ? project.getWorkspaceRepoBranch() : "main")
                    .repoToken(repoToken)
                    .build();
            log.debug("Workspace config from project: repo={}, branch={}, hasToken={}", 
                    project.getWorkspaceRepoUrl(), config.getBranch(), repoToken != null);
            return config;
        }
        
        return null;
    }

    /**
     * Filter documents by appliesTo glob patterns.
     * Documents match if:
     * 1. They have no appliesTo (universal)
     * 2. Any of their appliesTo patterns match the step path
     */
    private List<KnowledgeDocument> filterByAppliesTo(List<KnowledgeDocument> docs, String stepPath) {
        List<KnowledgeDocument> matched = new ArrayList<>();

        for (KnowledgeDocument doc : docs) {
            if (doc.getAppliesTo() == null || doc.getAppliesTo().isEmpty()) {
                // Universal document - always include
                matched.add(doc);
                continue;
            }

            // Check if any pattern matches
            for (String pattern : doc.getAppliesTo()) {
                if (matchesGlob(pattern, stepPath)) {
                    matched.add(doc);
                    break;
                }
            }
        }

        return matched;
    }

    /**
     * Check if a path matches a glob pattern.
     */
    private boolean matchesGlob(String pattern, String path) {
        try {
            // Handle simple patterns without glob syntax
            if (!pattern.contains("*") && !pattern.contains("?") && !pattern.contains("[")) {
                return path.contains(pattern) || pattern.equals("**");
            }

            // Use Java's PathMatcher for glob patterns
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            return matcher.matches(java.nio.file.Path.of(path));
        } catch (Exception e) {
            log.warn("Invalid glob pattern '{}': {}", pattern, e.getMessage());
            return false;
        }
    }

    /**
     * Build RagConfig from project's raw config map.
     */
    private TaskContext.RagConfig buildRagConfig(Map<String, Object> config) {
        return TaskContext.RagConfig.builder()
                .endpoint(getString(config, "endpoint"))
                .apiKeySecret(getString(config, "api_key_secret"))
                .collection(getString(config, "collection"))
                .topK(getInt(config, "top_k", 5))
                .build();
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * Resolve git authentication material from a secret reference.
     *
     * <p>BEARER_TOKEN → the token itself. USERNAME_PASSWORD → {@code user:pass}
     * basic-auth form (the agent stitches it into the clone URL). SSL_PRIVATE_KEY
     * is not yet plumbed through to the agent; returns null with a warning.
     *
     * @return string suitable for {@code Task.knowledge.workspace.repo_token}, or null
     */
    private String resolveGitTokenBySecretId(UUID projectId, UUID secretId) {
        if (secretId == null) {
            return null;
        }
        try {
            SecretPayload payload = secretResolver.resolveOneOf(secretId, projectId,
                    CredentialType.BEARER_TOKEN,
                    CredentialType.USERNAME_PASSWORD,
                    CredentialType.SSL_PRIVATE_KEY);
            return switch (payload) {
                case SecretPayload.BearerToken b -> b.token();
                case SecretPayload.UsernamePassword up -> up.username() + ":" + up.password();
                case SecretPayload.SslPrivateKey ignored -> {
                    log.warn("SSL_PRIVATE_KEY secret {} cannot yet be propagated to agent workspace; skipping",
                            secretId);
                    yield null;
                }
                default -> null;
            };
        } catch (SecretTypeMismatchException e) {
            log.warn("Workspace credential secret {} has unsupported type: {}", secretId, e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Failed to resolve workspace credential secret {} for project {}: {}",
                    secretId, projectId, e.getMessage());
            return null;
        }
    }

    private UUID getUuid(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof UUID u) return u;
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
