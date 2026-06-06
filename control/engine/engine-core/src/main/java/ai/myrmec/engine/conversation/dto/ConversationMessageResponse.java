package ai.myrmec.engine.conversation.dto;

import ai.myrmec.engine.conversation.ConversationMessage;

import java.time.Instant;
import java.util.UUID;

public record ConversationMessageResponse(
        UUID id,
        UUID conversationId,
        long sequenceNo,
        String role,
        String content,
        UUID authorUserId,
        UUID authorAgentId,
        String modelCode,
        Integer tokenCount,
        String toolCallId,
        UUID parentMessageId,
        Instant createdAt
) {
    public static ConversationMessageResponse from(ConversationMessage m) {
        return new ConversationMessageResponse(
                m.getId(),
                m.getConversationId(),
                m.getSequenceNo(),
                m.getRole() == null ? null : m.getRole().name(),
                m.getContent(),
                m.getAuthorUserId(),
                m.getAuthorAgentId(),
                m.getModelCode(),
                m.getTokenCount(),
                m.getToolCallId(),
                m.getParentMessageId(),
                m.getCreatedAt()
        );
    }
}
