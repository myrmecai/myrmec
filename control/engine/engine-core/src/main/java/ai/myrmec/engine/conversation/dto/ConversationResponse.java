package ai.myrmec.engine.conversation.dto;

import ai.myrmec.engine.conversation.Conversation;

import java.time.Instant;
import java.util.UUID;

public record ConversationResponse(
        UUID id,
        UUID projectId,
        UUID agentId,
        String title,
        String status,
        String systemPromptOverride,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt
) {
    public static ConversationResponse from(Conversation c) {
        return new ConversationResponse(
                c.getId(),
                c.getProjectId(),
                c.getAgentId(),
                c.getTitle(),
                c.getStatus() == null ? null : c.getStatus().name(),
                c.getSystemPromptOverride(),
                c.getCreatedBy(),
                c.getCreatedAt(),
                c.getUpdatedAt()
        );
    }
}
