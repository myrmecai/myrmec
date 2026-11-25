// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.mywork.dto;

import ai.myrmec.engine.assistant.Assistant;
import ai.myrmec.engine.conversation.Conversation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One Assistants-section row (UC-013) with the caller's own conversations
 * nested under it. Unlike {@link MyAssistantRow} (the flat roll-up), this tree
 * shape carries each assistant's {@link AssistantConversation} children so the
 * UI renders the parent/child relationship in one payload.
 *
 * <p>{@code conversations} holds only the <em>signed-in user's own</em>
 * ACTIVE conversations for this assistant (the Continue-rail "own recent
 * sessions" semantic), newest first.</p>
 */
public record AssistantTreeNode(
        UUID id,
        UUID teamId,
        String teamName,
        String name,
        String description,
        String status,
        long activeSessions,
        Instant lastSessionAt,
        List<AssistantConversation> conversations) {

    /** One of the caller's own conversations nested under an assistant. */
    public record AssistantConversation(
            UUID conversationId,
            String title,
            String state,
            Instant lastMessageAt) {

        public static AssistantConversation of(Conversation c, String state) {
            return new AssistantConversation(
                    c.getId(),
                    c.getTitle(),
                    state,
                    c.getUpdatedAt());
        }
    }

    public static AssistantTreeNode of(
            Assistant a,
            String teamName,
            long activeSessions,
            Instant lastSessionAt,
            List<AssistantConversation> conversations) {
        return new AssistantTreeNode(
                a.getId(),
                a.getTeamId(),
                teamName,
                a.getName(),
                a.getDescription(),
                MyAssistantRow.deriveStatus(a),
                activeSessions,
                lastSessionAt,
                conversations);
    }
}
