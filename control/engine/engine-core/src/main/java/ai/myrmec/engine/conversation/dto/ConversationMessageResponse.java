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
        String payloadJson,
        String approvalStatus,
        UUID approverId,
        Instant expiresAt,
        boolean pinned,
        String feedbackRating,
        String feedbackReason,
        UUID feedbackBy,
        Instant feedbackAt,
        boolean superseded,
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
                m.getPayloadJson(),
                m.getApprovalStatus() == null ? null : m.getApprovalStatus().name(),
                m.getApproverId(),
                m.getExpiresAt(),
                m.isPinned(),
                m.getFeedbackRating() == null ? null : m.getFeedbackRating().name(),
                m.getFeedbackReason(),
                m.getFeedbackBy(),
                m.getFeedbackAt(),
                m.isSuperseded(),
                m.getCreatedAt()
        );
    }
}
