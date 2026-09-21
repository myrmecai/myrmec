package ai.myrmec.engine.conversation.dto;

import jakarta.validation.constraints.Size;

/**
 * Rename-only partial update for a conversation's owner-editable metadata
 * (the chat {@code ...} menu).
 *
 * <p>Status transitions are no longer reachable here (2026-09-21 close/
 * archive lifecycle design section 4.3): closing is {@code POST /{id}/close}
 * and archiving is {@code POST /{id}/archive}. Old callers that still send a
 * {@code status} property see it silently dropped (Jackson ignores unknown
 * properties by default) - the PATCH surface is rename-only.</p>
 */
public record UpdateConversationRequest(
        @Size(max = 255) String title
) {
}
