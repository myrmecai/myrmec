package ai.myrmec.engine.external.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /external/conversations/{id}/messages} — a single user turn.
 */
public record PostMessageRequest(
        @NotBlank(message = "Message content is required.")
        @Size(max = 32_000, message = "Message content is too long.")
        String content) {
}
