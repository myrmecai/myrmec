package ai.myrmec.engine.external.dto;

import ai.myrmec.engine.conversation.ConversationMessage;

import java.time.Instant;
import java.util.UUID;

/**
 * External-API view of a single conversation message. The {@code sequenceNo}
 * doubles as the {@code since} cursor for incremental polling.
 */
public record ExternalMessageResponse(
        UUID id,
        long sequenceNo,
        String role,
        String content,
        Instant createdAt) {

    public static ExternalMessageResponse of(ConversationMessage m) {
        return new ExternalMessageResponse(
                m.getId(),
                m.getSequenceNo(),
                m.getRole() == null ? null : m.getRole().name(),
                m.getContent(),
                m.getCreatedAt());
    }
}
