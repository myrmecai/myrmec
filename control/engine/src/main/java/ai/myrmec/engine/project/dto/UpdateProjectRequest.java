package ai.myrmec.engine.project.dto;

import ai.myrmec.engine.project.ProjectStatus;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * Request DTO for updating a project.
 */
@Data
public class UpdateProjectRequest {

    @Size(max = 200, message = "Project name cannot exceed 200 characters")
    private String name;

    @Size(max = 3000, message = "Project description cannot exceed 3000 characters")
    private String description;

    private ProjectStatus status;

    /**
     * Default workspace git repository URL.
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
     * Pass an empty string / null to clear; pass a UUID string to set.
     */
    private String workspaceCredentialSecretId;

    /**
     * External RAG configuration.
     */
    private Map<String, Object> ragConfig;
}
