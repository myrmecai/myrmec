package ai.myrmec.engine.team.dto;

import ai.myrmec.engine.team.TeamStatus;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Request DTO for updating a Team.
 */
@Data
public class UpdateTeamRequest {

    @Size(max = 200, message = "Team name cannot exceed 200 characters")
    private String name;

    @Size(max = 3000, message = "Team description cannot exceed 3000 characters")
    private String description;

    private TeamStatus status;

    /**
     * Service types this Team may host (#77). Null preserves the current
     * value; a non-null list replaces it (must be non-empty and contain only
     * known service types).
     */
    private List<String> allowedServiceTypes;

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
     * Optional credential secret ID (Team-scoped or global) used to clone the workspace repo.
     * Pass an empty string / null to clear; pass a UUID string to set.
     */
    private String workspaceCredentialSecretId;

    /**
     * HITL policy switch. Null preserves the current value.
     */
    private Boolean autoHitlOnDestructive;

    /**
     * #105 — Team-level attachment governance posture.
     * Null preserves the current value.
     */
    private Boolean attachmentsEnabled;

    /**
     * Optional per-Team attachment retention TTL in days.
     * Null preserves the current value.
     */
    private Integer attachmentRetentionTtlDays;

    /**
     * Optional per-Team maximum attachment size in bytes.
     * Null preserves the current value.
     */
    private Long attachmentMaxFileSizeBytes;

    /**
     * Optional comma-separated MIME allowlist for Team attachments.
     * Null preserves the current value.
     */
    private String attachmentTypeAllowlist;
}
