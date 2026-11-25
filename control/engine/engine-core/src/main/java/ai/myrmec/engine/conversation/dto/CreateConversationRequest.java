package ai.myrmec.engine.conversation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request to create a new conversation from an assistant.
 *
 * <p>The assistant owns the Team (1:1), so the team is derived server-side.
 * {@code title} is free-form metadata used by the UI for the conversation
 * list. The agent/host and profile version are runtime concerns resolved at
 * start, not creation.</p>
 */
public record CreateConversationRequest(
        @NotNull UUID assistantId,
        @Size(max = 255) String title
) {
}
