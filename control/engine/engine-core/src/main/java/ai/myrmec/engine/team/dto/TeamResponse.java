package ai.myrmec.engine.team.dto;

import ai.myrmec.engine.team.Team;
import ai.myrmec.engine.team.TeamStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Response DTO for Team data.
 */
@Data
@Builder
public class TeamResponse {

    private UUID id;
    private String name;
    private String description;
    private UUID groupId;
    private TeamStatus status;
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

    public static TeamResponse from(Team team) {
        return TeamResponse.builder()
                .id(team.getId())
                .name(team.getName())
                .description(team.getDescription())
                .groupId(team.getGroupId())
                .status(team.getStatus())
                .allowedServiceTypes(team.getAllowedServiceTypes())
                .workspaceRepoUrl(team.getWorkspaceRepoUrl())
                .workspaceRepoBranch(team.getWorkspaceRepoBranch())
                .workspaceCredentialSecretId(team.getWorkspaceCredentialSecretId())
                .autoHitlOnDestructive(team.isAutoHitlOnDestructive())
                .attachmentsEnabled(team.isAttachmentsEnabled())
                .attachmentRetentionTtlDays(team.getAttachmentRetentionTtlDays())
                .attachmentMaxFileSizeBytes(team.getAttachmentMaxFileSizeBytes())
                .attachmentTypeAllowlist(team.getAttachmentTypeAllowlist())
                .createdAt(team.getCreatedAt())
                .updatedAt(team.getUpdatedAt())
                .build();
    }
}
