package ai.myrmec.engine.conversation.dto;

import ai.myrmec.engine.conversation.Conversation;

import java.time.Instant;
import java.util.UUID;

public record ConversationResponse(
        UUID id,
        UUID teamId,
        UUID assistantId,
        UUID assistantVersionId,
        String title,
        String status,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt,
        Instant closedAt,
        String closeReason,
        UUID closedBy,
        Instant archivedAt,
        String archiveReason,
        UUID archivedBy
) {
    public static ConversationResponse from(Conversation c) {
        return new ConversationResponse(
                c.getId(),
                c.getTeamId(),
                c.getAssistantId(),
                c.getAssistantVersionId(),
                c.getTitle(),
                c.getStatus() == null ? null : c.getStatus().name(),
                c.getCreatedBy(),
                c.getCreatedAt(),
                c.getUpdatedAt(),
                c.getClosedAt(),
                c.getCloseReason(),
                c.getClosedBy(),
                c.getArchivedAt(),
                c.getArchiveReason(),
                c.getArchivedBy()
        );
    }
}
