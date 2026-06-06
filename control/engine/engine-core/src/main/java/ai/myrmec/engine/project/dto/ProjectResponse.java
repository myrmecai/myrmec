package ai.myrmec.engine.project.dto;

import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Response DTO for project data.
 */
@Data
@Builder
public class ProjectResponse {

    private UUID id;
    private String name;
    private String description;
    private UUID groupId;
    private ProjectStatus status;
    private String workspaceRepoUrl;
    private String workspaceRepoBranch;
    private UUID workspaceCredentialSecretId;
    private Map<String, Object> ragConfig;
    private Instant createdAt;
    private Instant updatedAt;

    public static ProjectResponse from(Project project) {
        return ProjectResponse.builder()
                .id(project.getId())
                .name(project.getName())
                .description(project.getDescription())
                .groupId(project.getGroupId())
                .status(project.getStatus())
                .workspaceRepoUrl(project.getWorkspaceRepoUrl())
                .workspaceRepoBranch(project.getWorkspaceRepoBranch())
                .workspaceCredentialSecretId(project.getWorkspaceCredentialSecretId())
                .ragConfig(project.getRagConfig())
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .build();
    }
}
