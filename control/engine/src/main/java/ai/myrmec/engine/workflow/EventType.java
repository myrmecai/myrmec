package ai.myrmec.engine.workflow;

/**
 * Types of execution events.
 */
public enum EventType {
    /**
     * Log message from agent (DEBUG, INFO, WARN, ERROR).
     */
    LOG,

    /**
     * Progress update (0-100%).
     */
    PROGRESS,

    /**
     * Tool invocation started.
     */
    TOOL_CALL,

    /**
     * Tool invocation completed (success or error).
     */
    TOOL_RESULT,

    /**
     * Task status changed (e.g., PENDING → RUNNING).
     */
    STATUS_CHANGE,

    /**
     * Token usage report from a single LLM call.
     * Data: { model, promptTokens, completionTokens, totalTokens, callId? }
     */
    TOKEN_USAGE,

    /**
     * Per-task metrics summary (emitted at task completion).
     * Data: { totalDurationMs, modelDurationMs, toolDurationMs,
     *         modelCallCount, toolCallCount,
     *         promptTokens, completionTokens, totalTokens }
     */
    TASK_METRICS
}
