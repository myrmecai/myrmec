package ai.myrmec.engine.agent.dto;

import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.agent.AgentProfileVersion;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Response DTO for agent profile. Zone 1 fields come from the parent row;
 * the behaviour contract (Zone 2) is resolved from the profile's currently
 * published version (design §16.1). A profile without a published version
 * reports empty behaviour fields — the runtime refuses to run it.
 */
@Data
@Builder
public class AgentProfileResponse {

    private UUID id;
    private String name;
    private String description;
    private List<String> capabilities;
    private Set<String> toolCodes;
    private String systemPrompt;
    private String defaultModel;
    private String interactionMode;
    private String status;
    /** The published version's ID — the runtime pin target. */
    private UUID publishedVersionId;
    /** The published version's number, for the UI version banner. */
    private Integer publishedVersionNumber;
    private Instant createdAt;
    private Instant updatedAt;

    public static AgentProfileResponse from(AgentProfile profile, AgentProfileVersion published) {
        return AgentProfileResponse.builder()
                .id(profile.getId())
                .name(profile.getName())
                .description(profile.getDescription())
                .capabilities(published != null ? published.getCapabilities() : List.of())
                .toolCodes(published != null && published.getTools() != null
                    ? published.getTools().stream()
                        .map(ai.myrmec.engine.tool.Tool::getCode)
                        .collect(Collectors.toSet())
                    : Set.of())
                .systemPrompt(published != null ? published.getSystemPrompt() : null)
                .defaultModel(published != null ? published.getDefaultModel() : null)
                .interactionMode(published != null ? published.getInteractionMode().name() : null)
                .status(profile.getStatus().name())
                .publishedVersionId(published != null ? published.getId() : null)
                .publishedVersionNumber(published != null ? published.getVersionNumber() : null)
                .createdAt(profile.getCreatedAt())
                .updatedAt(profile.getUpdatedAt())
                .build();
    }
}
