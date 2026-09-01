package ai.myrmec.engine;

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
