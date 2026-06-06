package ai.myrmec.engine.project;

import ai.myrmec.engine._system.common.JsonMapConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Project entity - container for workflows.
 * Uses UUID as primary key.
 */
@Entity
@Table(name = "projects")
@Getter
@Setter
@NoArgsConstructor
public class Project {

    /**
     * Auto-generated UUID identifier.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, unique = true, length = 200)
    private String name;

    /**
     * Group that owns this project. Required: every project belongs to exactly
     * one group. Defaults to the seeded "Default" group when not specified.
     */
    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProjectStatus status = ProjectStatus.ACTIVE;

    /**
     * Default workspace git repository URL.
     * Used as the default work/commit repo for tasks when a workflow does not
     * specify its own artifactsRepo. Not used for knowledge syncing.
     */
    @Column(name = "workspace_repo_url", length = 500)
    private String workspaceRepoUrl;

    /**
     * Default workspace git branch (default: "main").
     */
    @Column(name = "workspace_repo_branch", length = 100)
    private String workspaceRepoBranch = "main";

    /**
     * Optional credential secret used when cloning the workspace repo.
     * References {@code secrets.id}; may point to a project-scoped or global secret.
     */
    @Column(name = "workspace_credential_secret_id")
    private UUID workspaceCredentialSecretId;

    /**
     * External RAG configuration for agent knowledge retrieval.
     * Structure: {endpoint, api_key_secret, collection, top_k}
     */
    @Convert(converter = JsonMapConverter.class)
    @Column(name = "rag_config")
    private Map<String, Object> ragConfig;

    /**
     * HITL policy switch (Phase 7b). When true, the dispatcher must
     * request human approval before executing any tool whose
     * {@code risk_class} is DESTRUCTIVE or IRREVERSIBLE. Defaults to
     * false on existing rows (preserves Phase 6 behaviour).
     */
    @Column(name = "auto_hitl_on_destructive", nullable = false)
    private boolean autoHitlOnDestructive = false;

    /**
     * Phase 9a — number of days to retain {@code execution_snapshots}
     * rows. Default 90. The (future) pruner will use this; the writer
     * does not.
     */
    @Column(name = "snapshot_retention_days", nullable = false)
    private int snapshotRetentionDays = 90;

    /**
     * Phase 9a — probability in [0.0, 1.0] that the snapshot writer
     * keeps any given inbound event. Default 1.0 (record everything).
     * Persisted as numeric(4,3) so the smallest representable step is
     * 0.001 (one in a thousand).
     */
    @Column(name = "snapshot_sampling_rate", nullable = false)
    private java.math.BigDecimal snapshotSamplingRate =
            java.math.BigDecimal.ONE.setScale(3, java.math.RoundingMode.UNNECESSARY);

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
