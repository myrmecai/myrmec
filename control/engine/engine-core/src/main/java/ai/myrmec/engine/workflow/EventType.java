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
    TASK_METRICS,

    /**
     * RAG retrieval audit event (emitted when an agent runs a
     * {@code ctx.retrieve()} query). Records the query and the returned
     * chunk IDs / source IDs / scores so an AUDITOR can replay exactly
     * which knowledge fed an answer; never the passage text itself.
     * Data: { knowledgeSourceId, query, topK, hitCount, chunkIds[],
     *         sourceIds[], scores[] }
     */
    RETRIEVAL,

    /**
     * Ordered orchestration progress metadata from the Agent (design
     * §16.3/§21). Idempotently ingested by
     * {@code OrchestrationEventIngestionService} keyed by the Agent-supplied
     * deterministic eventId, with a dispatch-local strictly-monotonic
     * sequence. Data: the redacted §21 event envelope (workerName, callId,
     * usage, candidateTreeHash, …).
     */
    ORCHESTRATION
}
