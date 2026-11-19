package ai.myrmec.engine.team.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Request DTO for creating a Team.
 */
@Data
public class CreateTeamRequest {

    @NotBlank(message = "Team name is required")
    @Size(max = 200, message = "Team name cannot exceed 200 characters")
    private String name;

    @Size(max = 3000, message = "Team description cannot exceed 3000 characters")
    private String description;

    /**
     * Service types this Team may host (#77). Omit / null defaults to both
     * shipped types ({@code WORKFLOW}, {@code CONVERSATIONAL}).
     */
    private List<String> allowedServiceTypes;

    /**
     * Group this Team belongs to. Defaults to the seeded {@code Default}
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
     * Optional credential secret ID (Team-scoped or global) used to clone the workspace repo.
     */
    private java.util.UUID workspaceCredentialSecretId;

    /**
     * When true, any tool with {@code risk_class} DESTRUCTIVE or
     * IRREVERSIBLE triggers a HITL approval before the agent may
     * execute it. Defaults to false (Phase 6 behaviour preserved).
     */
    private Boolean autoHitlOnDestructive;

    /**
     * #105 — Team-level attachment governance posture.
     */
    private Boolean attachmentsEnabled;

    /**
     * Optional per-Team attachment retention TTL in days.
     */
    private Integer attachmentRetentionTtlDays;

    /**
     * Optional per-Team maximum attachment size in bytes.
     */
    private Long attachmentMaxFileSizeBytes;

    /**
     * Optional comma-separated MIME allowlist for Team attachments.
     */
    private String attachmentTypeAllowlist;
}
