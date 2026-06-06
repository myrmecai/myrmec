package ai.myrmec.engine.workflow;

import ai.myrmec.engine._system.common.JsonMapConverter;
import ai.myrmec.engine.agent.AgentInstance;
import ai.myrmec.engine.agent.AgentProfile;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WorkflowTask entity - execution of a single workflow step.
 */
@Entity
@Table(name = "workflow_tasks")
@Getter
@Setter
@NoArgsConstructor
public class WorkflowTask {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id", nullable = false)
    private WorkflowRequest request;

    /**
     * Step ID from workflow definition - used to match step config.
     */
    @Column(name = "step_id", nullable = false, length = 50)
    private String stepId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_profile_id", nullable = false)
    private AgentProfile agentProfile;

    /**
     * Agent instance that executed/is executing this task (null if not yet assigned).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_instance_id")
    private AgentInstance agentInstance;

    /**
     * Input to this task (from workflow input + previous step outputs).
     */
    @Convert(converter = JsonMapConverter.class)
    @Column(name = "input")
    private Map<String, Object> input;

    /**
     * Output from this task (passed to dependent steps).
     */
    @Convert(converter = JsonMapConverter.class)
    @Column(name = "output")
    private Map<String, Object> output;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TaskStatus status = TaskStatus.PENDING;

    /**
     * Result after completion - determines which transition to follow.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "result", length = 20)
    private TaskResult result;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    /**
     * Attempt number (1-based) for retries.
     */
    @Column(name = "attempt", nullable = false)
    private Integer attempt = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * Aggregated execution metrics (token usage, timing breakdown, cost).
     * Written by the engine on task completion from TOKEN_USAGE / TASK_METRICS events.
     * Shape (best-effort, all fields optional):
     * {
     *   promptTokens, completionTokens, totalTokens,
     *   modelCallCount, toolCallCount,
     *   totalDurationMs, modelDurationMs, toolDurationMs,
     *   costUsd, currency, model
     * }
     */
    @Convert(converter = JsonMapConverter.class)
    @Column(name = "metrics")
    private Map<String, Object> metrics;

    // ==========================================================================
    // NEW: Attempts support
    // ==========================================================================

    /**
     * All attempts for this task.
     */
    @OneToMany(mappedBy = "task", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("attemptNumber ASC")
    private List<TaskAttempt> attempts = new ArrayList<>();

    /**
     * Current/latest attempt for this task (null if never started).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_attempt_id")
    private TaskAttempt currentAttempt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    /**
     * Create a new attempt for this task.
     */
    public TaskAttempt createAttempt(AgentInstance agentInstance) {
        TaskAttempt attempt = new TaskAttempt();
        attempt.setTask(this);
        attempt.setAttemptNumber(this.attempt);
        attempt.setAgentInstance(agentInstance);
        attempt.setStatus(AttemptStatus.RUNNING);
        this.attempts.add(attempt);
        this.currentAttempt = attempt;
        return attempt;
    }

    /**
     * Get the latest attempt result output (convenience method).
     */
    public Map<String, Object> getLatestOutput() {
        if (currentAttempt != null && currentAttempt.getOutput() != null) {
            return currentAttempt.getOutput();
        }
        return output; // fallback to legacy field
    }
}
