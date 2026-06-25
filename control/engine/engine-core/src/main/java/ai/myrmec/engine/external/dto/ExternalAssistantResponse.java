package ai.myrmec.engine.external.dto;

import ai.myrmec.engine.external.ExternalAssistantService.UsableAssistant;

import java.util.UUID;

/**
 * Discovery view of an assistant exposed to the External API — the minimal,
 * non-sensitive surface a caller needs to choose an assistant and render its
 * opening greeting. Internal wiring (agent profile ids, tool config, grants)
 * is deliberately omitted.
 */
public record ExternalAssistantResponse(
        UUID id,
        String name,
        String description,
        String version,
        String greeting) {

    public static ExternalAssistantResponse of(UsableAssistant usable) {
        return new ExternalAssistantResponse(
                usable.assistant().getId(),
                usable.assistant().getName(),
                usable.assistant().getDescription(),
                usable.version().getVersionNumber(),
                usable.version().getGreetingMessage());
    }
}
