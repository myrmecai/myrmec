package ai.myrmec.engine.external.dto;

import ai.myrmec.engine.conversation.Conversation;

import java.time.Instant;
import java.util.UUID;

/**
 * External-API view of a {@link Conversation}. Exposes only the
 * fields a caller needs to track and resume an end user's thread.
 */
public record ExternalConversationResponse(
        UUID id,
        UUID assistantId,
        String status,
        String externalUserRef,
        Instant createdAt,
        Instant updatedAt) {

    public static ExternalConversationResponse of(Conversation c) {
        return new ExternalConversationResponse(
                c.getId(),
                c.getAssistantId(),
                c.getStatus() == null ? null : c.getStatus().name(),
                c.getExternalUserRef(),
                c.getCreatedAt(),
                c.getUpdatedAt());
    }
}
