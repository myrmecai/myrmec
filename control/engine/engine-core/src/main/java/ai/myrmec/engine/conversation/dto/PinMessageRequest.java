package ai.myrmec.engine.conversation.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Set or clear the participant pin flag on a single conversation message.
 */
public record PinMessageRequest(
        @NotNull Boolean pinned
) {
}
