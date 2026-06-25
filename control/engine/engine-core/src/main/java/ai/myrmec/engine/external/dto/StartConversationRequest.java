package ai.myrmec.engine.external.dto;

import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /external/assistants/{id}/conversations}. The first user
 * message is optional — a caller may open an empty conversation and post turns
 * later. The end-user reference travels in the {@code X-Myrmec-End-User-Ref}
 * header, not the body.
 */
public record StartConversationRequest(
        @Size(max = 32_000, message = "First message is too long.")
        String firstMessage) {
}
