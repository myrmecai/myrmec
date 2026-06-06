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
