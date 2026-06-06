package ai.myrmec.engine.conversation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request to create a new conversation under a project.
 *
 * <p>{@code agentId} is optional — when omitted, the conversation has no
 * default agent and individual messages must select one. {@code title}
 * is free-form metadata used by the UI for the conversation list.</p>
 */
public record CreateConversationRequest(
        @NotNull UUID projectId,
        UUID agentId,
        @Size(max = 255) String title,
        @Size(max = 4000) String systemPromptOverride
) {
}
