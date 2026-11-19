package ai.myrmec.engine.agent.dto;

import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.agent.AgentProfileVersion;
import ai.myrmec.engine.agent.AgentProfileVersionService;
import ai.myrmec.engine.agent.dto.AgentProfileCapabilityInfo;
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
    private List<AgentProfileCapabilityInfo> capabilities;
    private Set<String> toolCodes;
    private String systemPrompt;
    private String defaultModel;
    private String interactionMode;
    private String status;
    /** The published version's ID — the runtime pin target. */
    private UUID publishedVersionId;
    /** The published version's number, for the UI version banner. */
    private Integer publishedVersionNumber;
    /** The single open draft's ID when one exists (the UI draft banner). */
    private UUID draftVersionId;
    /** The open draft's version number. */
    private Integer draftVersionNumber;
    private Instant createdAt;
    private Instant updatedAt;

    public static AgentProfileResponse from(
            AgentProfile profile,
            AgentProfileVersion published,
            AgentProfileVersionService versionService) {
        return from(profile, published, null, versionService);
    }

    public static AgentProfileResponse from(
            AgentProfile profile,
            AgentProfileVersion published,
            AgentProfileVersion openDraft,
            AgentProfileVersionService versionService) {
        return AgentProfileResponse.builder()
                .id(profile.getId())
                .name(profile.getName())
                .description(profile.getDescription())
                .capabilities(published != null
                    ? versionService.getCapabilities(published.getId())
                    : List.of())
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
                .draftVersionId(openDraft != null ? openDraft.getId() : null)
                .draftVersionNumber(openDraft != null ? openDraft.getVersionNumber() : null)
                .createdAt(profile.getCreatedAt())
                .updatedAt(profile.getUpdatedAt())
                .build();
    }

    /**
     * Public projection for authenticated non-admin surfaces (workflow
     * authoring, run views): identity + version pointers only — the
     * behaviour contract (system prompt, tools) and draft state stay
     * admin-only. Mirrors {@code ModelResponse.fromPublic}.
     */
    public static AgentProfileResponse fromPublic(
            AgentProfile profile,
            AgentProfileVersion published,
            AgentProfileVersionService versionService) {
        return AgentProfileResponse.builder()
                .id(profile.getId())
                .name(profile.getName())
                .description(profile.getDescription())
                .status(profile.getStatus().name())
                .interactionMode(published != null ? published.getInteractionMode().name() : null)
                .defaultModel(published != null ? published.getDefaultModel() : null)
                .publishedVersionId(published != null ? published.getId() : null)
                .publishedVersionNumber(published != null ? published.getVersionNumber() : null)
                .capabilities(published != null ? versionService.getCapabilities(published.getId()) : List.of())
                .build();
    }
}
