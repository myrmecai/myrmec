package ai.myrmec.engine.conversation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request to append a user-authored message to a conversation. The
 * author is taken from the authenticated principal so it cannot be
 * spoofed via the request body.
 */
public record PostUserMessageRequest(
        @NotBlank @Size(max = 32_000) String content
) {
}
