package ai.myrmec.engine.project.dto;

import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
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
    private List<String> allowedServiceTypes;
    private String workspaceRepoUrl;
    private String workspaceRepoBranch;
    private UUID workspaceCredentialSecretId;
    private boolean autoHitlOnDestructive;
    private boolean attachmentsEnabled;
    private Integer attachmentRetentionTtlDays;
    private Long attachmentMaxFileSizeBytes;
    private String attachmentTypeAllowlist;
    private Instant createdAt;
    private Instant updatedAt;

    public static ProjectResponse from(Project project) {
        return ProjectResponse.builder()
                .id(project.getId())
                .name(project.getName())
                .description(project.getDescription())
                .groupId(project.getGroupId())
                .status(project.getStatus())
                .allowedServiceTypes(project.getAllowedServiceTypes())
                .workspaceRepoUrl(project.getWorkspaceRepoUrl())
                .workspaceRepoBranch(project.getWorkspaceRepoBranch())
                .workspaceCredentialSecretId(project.getWorkspaceCredentialSecretId())
                .autoHitlOnDestructive(project.isAutoHitlOnDestructive())
                .attachmentsEnabled(project.isAttachmentsEnabled())
                .attachmentRetentionTtlDays(project.getAttachmentRetentionTtlDays())
                .attachmentMaxFileSizeBytes(project.getAttachmentMaxFileSizeBytes())
                .attachmentTypeAllowlist(project.getAttachmentTypeAllowlist())
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .build();
    }
}
