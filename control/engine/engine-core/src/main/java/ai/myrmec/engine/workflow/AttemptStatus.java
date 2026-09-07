package ai.myrmec.engine.workflow;

/**
 * Status of a task attempt.
 */
public enum AttemptStatus {
    /** Attempt is running */
    RUNNING,
    /** Attempt completed (check result for SUCCESS/FAILURE) */
    COMPLETED,
    /** Attempt failed */
    FAILED,
    /** Attempt was abandoned (connection lost, heartbeat timeout) */
    ABANDONED,
    /** Attempt was skipped by the agent (preconditions not met) */
    SKIPPED,
    /** Orchestration suspension awaiting HITL review (design §16.6 — the attempt keeps its signed output immutable) */
    PAUSED
}
