package ai.myrmec.engine.workflow;

import ai.myrmec.engine._system.common.JsonMapConverter;
import ai.myrmec.engine.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * WorkflowRequest entity - an execution instance of a workflow.
 */
@Entity
@Table(name = "workflow_requests")
@Getter
@Setter
@NoArgsConstructor
public class WorkflowRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_id", nullable = false)
    private Workflow workflow;

    /**
     * Version of workflow at time of request creation (snapshot).
     */
    @Column(name = "workflow_version", nullable = false)
    private Integer workflowVersion;

    /**
     * Input data for the workflow (validated against inputSchema if present).
     */
    @Convert(converter = JsonMapConverter.class)
    @Column(name = "input")
    private Map<String, Object> input;

    /**
     * Final output from the workflow (collected from terminal steps).
     */
    @Convert(converter = JsonMapConverter.class)
    @Column(name = "output")
    private Map<String, Object> output;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RequestStatus status = RequestStatus.PENDING;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    /**
     * Feature branch for this execution. All tasks operate on this branch.
     * Generated at workflow start: myrmec/{short-id}-{sanitized-feature-name}
     */
    @Column(name = "branch", length = 200)
    private String branch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
