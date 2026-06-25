package ai.myrmec.engine.conversation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request to create a new conversation under a project.
 *
 * <p>{@code agentId} is optional — when omitted, the conversation has no
 * default agent and individual messages must select one. {@code title}
 * is free-form metadata used by the UI for the conversation list.
 * {@code assistantId}, when supplied (#92), pins the assistant's currently
 * published version onto the conversation for the life of the session.</p>
 */
public record CreateConversationRequest(
        @NotNull UUID projectId,
        UUID agentId,
        @Size(max = 255) String title,
        @Size(max = 4000) String systemPromptOverride,
        UUID assistantId
) {
    /** Backward-compatible constructor for callers without an assistant. */
    public CreateConversationRequest(UUID projectId, UUID agentId, String title, String systemPromptOverride) {
        this(projectId, agentId, title, systemPromptOverride, null);
    }
}
