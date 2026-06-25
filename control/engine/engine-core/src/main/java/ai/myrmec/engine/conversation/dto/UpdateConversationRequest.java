package ai.myrmec.engine.conversation.dto;

import jakarta.validation.constraints.Size;

/**
 * Partial update for a conversation's owner-editable metadata (the chat
 * {@code ⋯} menu): rename and archive / unarchive.
 *
 * <p>Both fields are optional — a {@code null} field is left untouched, so
 * a rename and a status change can be applied independently or together.
 * {@code status} accepts only {@code ACTIVE} or {@code ARCHIVED}; the
 * destructive {@code DELETED} transition is intentionally not reachable
 * through this endpoint.</p>
 */
public record UpdateConversationRequest(
        @Size(max = 255) String title,
        @Size(max = 20) String status
) {
}
