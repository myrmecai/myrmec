package ai.myrmec.engine;

import ai.myrmec.engine.knowledge.KnowledgeCategory;
import ai.myrmec.engine.knowledge.KnowledgeDocument;
import ai.myrmec.engine.knowledge.KnowledgeScope;
import ai.myrmec.engine.model.DeploymentType;
import ai.myrmec.engine.model.Model;
import ai.myrmec.engine.model.ModelStatus;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Factory for creating test data entities.
 * Provides fluent builders with sensible defaults for common test scenarios.
 */
public class TestDataFactory {

    // ==================== Project Builders ====================

    /**
     * Fluent builder for Project entity.
     */
    public static class ProjectBuilder {
        private final Project project;

        private ProjectBuilder(String name) {
            this.project = new Project();
            this.project.setName(name);
            this.project.setGroupId(ai.myrmec.engine.group.Group.DEFAULT_GROUP_ID);
            this.project.setStatus(ProjectStatus.ACTIVE);
            this.project.setCreatedAt(Instant.now());
        }

        public ProjectBuilder description(String description) {
            project.setDescription(description);
            return this;
        }

        public ProjectBuilder status(ProjectStatus status) {
            project.setStatus(status);
            return this;
        }

        public ProjectBuilder workspaceRepoUrl(String repoUrl) {
            project.setWorkspaceRepoUrl(repoUrl);
            return this;
        }

        public ProjectBuilder workspaceRepoBranch(String branch) {
            project.setWorkspaceRepoBranch(branch);
            return this;
        }

        public ProjectBuilder ragConfig(Map<String, Object> config) {
            project.setRagConfig(config);
            return this;
        }

        public Project build() {
            return project;
        }
    }

    /**
     * Create a project builder with name.
     */
    public static ProjectBuilder projectBuilder(String name) {
        return new ProjectBuilder(name);
    }

    // ==================== Knowledge Document Builders ====================

    /**
     * Fluent builder for KnowledgeDocument entity.
     */
    public static class KnowledgeDocumentBuilder {
        private final KnowledgeDocument doc;

        private KnowledgeDocumentBuilder(String name) {
            this.doc = new KnowledgeDocument();
            this.doc.setName(name);
            this.doc.setCategory(KnowledgeCategory.STANDARD);
            this.doc.setScope(KnowledgeScope.ORGANIZATION);
            this.doc.setPriority(100);
            this.doc.setActive(true);
            this.doc.setCreatedAt(Instant.now());
            this.doc.setCreatedBy(UUID.randomUUID());
        }

        public KnowledgeDocumentBuilder content(String content) {
            doc.setContent(content);
            return this;
        }

        public KnowledgeDocumentBuilder category(KnowledgeCategory category) {
            doc.setCategory(category);
            return this;
        }

        public KnowledgeDocumentBuilder organizationScope() {
            doc.setScope(KnowledgeScope.ORGANIZATION);
            doc.setProjectId(null);
            return this;
        }

        public KnowledgeDocumentBuilder projectScope(UUID projectId) {
            doc.setScope(KnowledgeScope.PROJECT);
            doc.setProjectId(projectId);
            return this;
        }

        public KnowledgeDocumentBuilder priority(int priority) {
            doc.setPriority(priority);
            return this;
        }

        public KnowledgeDocumentBuilder appliesTo(String... patterns) {
            doc.setAppliesTo(List.of(patterns));
            return this;
        }

        public KnowledgeDocumentBuilder appliesTo(List<String> patterns) {
            doc.setAppliesTo(patterns);
            return this;
        }

        public KnowledgeDocumentBuilder active(boolean active) {
            doc.setActive(active);
            return this;
        }

        public KnowledgeDocumentBuilder sourcePath(String path) {
            doc.setSourcePath(path);
            return this;
        }

        public KnowledgeDocumentBuilder createdBy(UUID userId) {
            doc.setCreatedBy(userId);
            return this;
        }

        public KnowledgeDocument build() {
            if (doc.getContent() == null) {
                doc.setContent("# " + doc.getName() + "\n\nDefault test content.");
            }
            return doc;
        }
    }

    /**
     * Create a knowledge document builder.
     */
    public static KnowledgeDocumentBuilder knowledgeDocument(String name) {
        return new KnowledgeDocumentBuilder(name);
    }

    // ==================== Convenience Methods ====================

    /**
     * Create an organization-level standard document.
     */
    public static KnowledgeDocument orgStandard(String name, String content, int priority) {
        return knowledgeDocument(name)
                .category(KnowledgeCategory.STANDARD)
                .organizationScope()
                .content(content)
                .priority(priority)
                .build();
    }

    /**
     * Create an organization-level requirement document.
     */
    public static KnowledgeDocument orgRequirement(String name, String content, int priority) {
        return knowledgeDocument(name)
                .category(KnowledgeCategory.REQUIREMENT)
                .organizationScope()
                .content(content)
                .priority(priority)
                .build();
    }

    /**
     * Create a project-level instruction document with appliesTo.
     */
    public static KnowledgeDocument projectInstruction(UUID projectId, String name, String content, 
                                                       int priority, String... appliesTo) {
        return knowledgeDocument(name)
                .category(KnowledgeCategory.INSTRUCTION)
                .projectScope(projectId)
                .content(content)
                .priority(priority)
                .appliesTo(appliesTo)
                .build();
    }

    /**
     * Create a project-level architecture document.
     */
    public static KnowledgeDocument projectArchitecture(UUID projectId, String name, String content, int priority) {
        return knowledgeDocument(name)
                .category(KnowledgeCategory.ARCHITECTURE)
                .projectScope(projectId)
                .content(content)
                .priority(priority)
                .build();
    }

    // ==================== Model Builders ====================

    /**
     * Fluent builder for Model entity.
     */
    public static class ModelBuilder {
        private final Model model;

        private ModelBuilder(String code) {
            this.model = new Model();
            this.model.setCode(code);
            this.model.setName(code);
            this.model.setDeploymentType(DeploymentType.CLOUD);
            this.model.setRequiresAuth(true);
            this.model.setStatus(ModelStatus.ACTIVE);
            this.model.setCreatedAt(Instant.now());
        }

        public ModelBuilder name(String name) {
            model.setName(name);
            return this;
        }

        public ModelBuilder provider(String provider) {
            model.setProvider(provider);
            return this;
        }

        public ModelBuilder modelId(String modelId) {
            model.setModelId(modelId);
            return this;
        }

        public ModelBuilder apiEndpoint(String endpoint) {
            model.setApiEndpoint(endpoint);
            return this;
        }

        public ModelBuilder apiKeyEncrypted(byte[] encrypted) {
            model.setApiKeyEncrypted(encrypted);
            return this;
        }

        public ModelBuilder deploymentType(DeploymentType type) {
            model.setDeploymentType(type);
            return this;
        }

        public ModelBuilder defaultParams(Map<String, Object> params) {
            model.setDefaultParams(params);
            return this;
        }

        public Model build() {
            return model;
        }
    }

    /**
     * Create a model builder with code.
     */
    public static ModelBuilder modelBuilder(String code) {
        return new ModelBuilder(code);
    }

    /**
     * Create a GitHub Models GPT-4o model (for E2E tests).
     * API key must be set separately using encryptionService.
     */
    public static Model githubGpt4o() {
        return modelBuilder("github-gpt-4o")
                .name("GPT-4o (GitHub Models)")
                .provider("github_models")
                .modelId("gpt-4o")
                .apiEndpoint("https://models.inference.ai.azure.com")
                .defaultParams(Map.of("temperature", 0.7))
                .build();
    }
}
