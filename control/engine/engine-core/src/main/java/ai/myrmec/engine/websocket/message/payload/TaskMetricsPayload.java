package ai.myrmec.engine.websocket.message.payload;

import lombok.Data;

import java.util.UUID;

/**
 * Payload for task.metrics message (Agent → Engine).
 * Sent once near task completion with agent-side aggregated metrics.
 * The engine may augment/override with its own aggregation from execution_events.
 */
@Data
public class TaskMetricsPayload {

    /** Task ID */
    private UUID taskId;

    /** Attempt ID */
    private UUID attemptId;

    /** Total wall-clock duration of the attempt, in ms */
    private Long totalDurationMs;

    /** Time spent in LLM calls, in ms */
    private Long modelDurationMs;

    /** Time spent in tool calls, in ms */
    private Long toolDurationMs;

    /** Number of LLM calls */
    private Integer modelCallCount;

    /** Number of tool calls */
    private Integer toolCallCount;

    /** Aggregated prompt tokens across all model calls */
    private Integer promptTokens;

    /** Aggregated completion tokens across all model calls */
    private Integer completionTokens;

    /** Aggregated total tokens */
    private Integer totalTokens;

    /** Primary LLM model used (best effort) */
    private String model;
}
