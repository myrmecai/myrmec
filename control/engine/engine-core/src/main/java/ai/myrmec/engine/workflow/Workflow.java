package ai.myrmec.engine.workflow;

import ai.myrmec.engine._system.common.JsonListMapConverter;
import ai.myrmec.engine._system.common.JsonMapConverter;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Workflow entity - defines a DAG of steps for agent execution.
 */
@Entity
@Table(name = "workflows")
@Getter
@Setter
@NoArgsConstructor
public class Workflow {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    /**
     * DAG steps as JSONB - list of WorkflowStep objects.
     */
    @Convert(converter = JsonListMapConverter.class)
    @Column(name = "steps", nullable = false)
    private List<Map<String, Object>> steps;

    /**
     * JSON Schema for workflow input validation (optional).
     */
    @Convert(converter = JsonMapConverter.class)
    @Column(name = "input_schema")
    private Map<String, Object> inputSchema;

    /**
     * Git repository configuration for code generation artifacts.
     * JSON: {url, baseBranch, credentialSecretId}
     */
    @Convert(converter = JsonMapConverter.class)
    @Column(name = "artifacts_repo")
    private Map<String, Object> artifactsRepo;

    @Column(name = "version", nullable = false)
    private Integer version = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WorkflowStatus status = WorkflowStatus.DRAFT;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
