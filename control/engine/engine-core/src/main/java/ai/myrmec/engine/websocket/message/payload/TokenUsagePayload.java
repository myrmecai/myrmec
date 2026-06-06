package ai.myrmec.engine.websocket.message.payload;

import lombok.Data;

import java.util.UUID;

/**
 * Payload for token.usage message (Agent → Engine).
 * Sent after each LLM call to report token consumption.
 */
@Data
public class TokenUsagePayload {

    /** Task ID */
    private UUID taskId;

    /** Attempt ID this usage belongs to */
    private UUID attemptId;

    /** LLM model identifier as known to the provider (e.g. "gpt-4o") */
    private String model;

    /** Optional correlation id linking back to a specific tool/model call */
    private String callId;

    /** Prompt (input) tokens */
    private Integer promptTokens;

    /** Completion (output) tokens */
    private Integer completionTokens;

    /** Total tokens reported by the provider (may differ from prompt+completion) */
    private Integer totalTokens;

    /** Optional duration of this LLM call in milliseconds */
    private Long durationMs;
}
