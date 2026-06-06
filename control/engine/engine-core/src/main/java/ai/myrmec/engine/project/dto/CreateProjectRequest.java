package ai.myrmec.engine.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * Request DTO for creating a project.
 */
@Data
public class CreateProjectRequest {

    @NotBlank(message = "Project name is required")
    @Size(max = 200, message = "Project name cannot exceed 200 characters")
    private String name;

    @Size(max = 3000, message = "Project description cannot exceed 3000 characters")
    private String description;

    /**
     * Group this project belongs to. Defaults to the seeded {@code Default}
     * group when omitted.
     */
    private java.util.UUID groupId;

    /**
     * Default workspace git repository URL used by tasks when a workflow does
     * not specify its own artifactsRepo.
     */
    @Size(max = 500, message = "Repository URL cannot exceed 500 characters")
    private String workspaceRepoUrl;

    /**
     * Default workspace git branch (default: "main").
     */
    @Size(max = 100, message = "Branch name cannot exceed 100 characters")
    private String workspaceRepoBranch;

    /**
     * Optional credential secret ID (project-scoped or global) used to clone the workspace repo.
     */
    private java.util.UUID workspaceCredentialSecretId;

    /**
     * External RAG configuration.
     */
    private Map<String, Object> ragConfig;
}
