package ai.myrmec.engine.workflow;

import ai.myrmec.engine._system.common.JsonMapConverter;
import ai.myrmec.engine.agent.AgentInstance;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * TaskAttempt entity - represents a single execution attempt of a workflow task.
 * 
 * <p>A task may have multiple attempts when:
 * <ul>
 *   <li>Agent fails and task is retried</li>
 *   <li>Agent connection is lost (attempt marked ABANDONED)</li>
 *   <li>Agent skips the task (preconditions not met)</li>
 * </ul>
 * 
 * <p>ABANDONED attempts do not count toward the retry limit.
 */
@Entity
@Table(name = "task_attempts", indexes = {
    @Index(name = "idx_task_attempts_task_id", columnList = "task_id, attempt_number")
})
@Getter
@Setter
@NoArgsConstructor
public class TaskAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Task this attempt belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false)
    private WorkflowTask task;

    /**
     * Attempt number (1-based).
     */
    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;

    /**
     * Agent instance executing this attempt (null if not yet assigned).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_instance_id")
    private AgentInstance agentInstance;

    /**
     * Status of this attempt.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AttemptStatus status = AttemptStatus.RUNNING;

    /**
     * Result after completion (SUCCESS/FAILURE).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "result", length = 20)
    private TaskResult result;

    /**
     * Output from this attempt (if successful).
     */
    @Convert(converter = JsonMapConverter.class)
    @Column(name = "output")
    private Map<String, Object> output;

    /**
     * Error message if attempt failed.
     */
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    /**
     * Structured error code for programmatic handling.
     */
    @Column(name = "error_code", length = 50)
    private String errorCode;

    /**
     * When this attempt started.
     */
    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    /**
     * When this attempt completed (null if still running).
     */
    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    protected void onCreate() {
        if (startedAt == null) {
            startedAt = Instant.now();
        }
    }

    /**
     * Mark this attempt as completed with success.
     */
    public void markSuccess(Map<String, Object> output) {
        this.status = AttemptStatus.COMPLETED;
        this.result = TaskResult.SUCCESS;
        this.output = output;
        this.completedAt = Instant.now();
    }

    /**
     * Mark this attempt as failed.
     */
    public void markFailed(String errorMessage, String errorCode) {
        this.status = AttemptStatus.FAILED;
        this.result = TaskResult.FAILURE;
        this.errorMessage = errorMessage;
        this.errorCode = errorCode;
        this.completedAt = Instant.now();
    }

    /**
     * Mark this attempt as abandoned (connection lost).
     */
    public void markAbandoned(String reason) {
        this.status = AttemptStatus.ABANDONED;
        this.errorMessage = reason;
        this.completedAt = Instant.now();
    }

    /**
     * Mark this attempt as skipped.
     */
    public void markSkipped(String reason) {
        this.status = AttemptStatus.SKIPPED;
        this.errorMessage = reason;
        this.completedAt = Instant.now();
    }

    /**
     * Check if this attempt is terminal (no longer running).
     */
    public boolean isTerminal() {
        return status != AttemptStatus.RUNNING;
    }

    /**
     * Check if this attempt counts toward retry limit.
     * ABANDONED and SKIPPED attempts don't count.
     */
    public boolean countsTowardRetryLimit() {
        return status == AttemptStatus.COMPLETED || status == AttemptStatus.FAILED;
    }
}
