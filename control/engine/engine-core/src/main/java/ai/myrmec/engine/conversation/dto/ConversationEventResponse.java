package ai.myrmec.engine.conversation.dto;

import ai.myrmec.engine.conversation.ConversationEvent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Read model for one row of the append-only conversation lifecycle log
 * (conversation-observability §4). Surfaces the worker-FSM transition, the
 * per-attempt worker/host attribution, and the reason code so operators can
 * replay why a conversation waited, bound, or dropped.
 */
public record ConversationEventResponse(
        UUID id,
        UUID conversationId,
        int seq,
        String fromState,
        String toState,
        String reasonCode,
        UUID agentId,
        UUID agentHostId,
        int bindAttemptNo,
        Map<String, Object> attributes,
        Instant occurredAt
) {
    public static ConversationEventResponse from(ConversationEvent e) {
        return new ConversationEventResponse(
                e.getId(),
                e.getConversationId(),
                e.getSeq(),
                e.getFromState(),
                e.getToState(),
                e.getReasonCode() == null ? null : e.getReasonCode().name(),
                e.getAgentId(),
                e.getAgentHostId(),
                e.getBindAttemptNo(),
                e.getAttributes(),
                e.getOccurredAt()
        );
    }
}
