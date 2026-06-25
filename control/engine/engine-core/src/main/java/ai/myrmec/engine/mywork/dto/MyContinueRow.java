package ai.myrmec.engine.mywork.dto;

import ai.myrmec.engine.conversation.Conversation;

import java.time.Instant;
import java.util.UUID;

/**
 * A chip in the My Work "Conversations" tab Continue rail (UC-013) — one of the
 * signed-in user's own recently-active sessions, for quick resume. Only the
 * caller's internal sessions appear; other people's sessions and external-API
 * sessions never surface here.
 */
public record MyContinueRow(
        UUID conversationId,
        String title,
        UUID assistantId,
        String assistantName,
        Instant lastMessageAt) {

    public static MyContinueRow of(Conversation c, String assistantName) {
        return new MyContinueRow(
                c.getId(),
                c.getTitle(),
                c.getAssistantId(),
                assistantName,
                c.getUpdatedAt());
    }
}
