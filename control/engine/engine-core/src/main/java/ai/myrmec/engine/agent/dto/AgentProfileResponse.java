package ai.myrmec.engine.agent.dto;

import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.tool.Tool;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Response DTO for agent profile.
 */
@Data
@Builder
public class AgentProfileResponse {

    private UUID id;
    private String name;
    private String description;
    private List<String> capabilities;
    private List<String> supportedTools;
    private Set<String> toolCodes;
    private String systemPrompt;
    private String defaultModel;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;

    public static AgentProfileResponse from(AgentProfile profile) {
        return AgentProfileResponse.builder()
                .id(profile.getId())
                .name(profile.getName())
                .description(profile.getDescription())
                .capabilities(profile.getCapabilities())
                .supportedTools(profile.getSupportedTools())
                .toolCodes(profile.getTools() != null 
                    ? profile.getTools().stream().map(Tool::getCode).collect(Collectors.toSet())
                    : Set.of())
                .systemPrompt(profile.getSystemPrompt())
                .defaultModel(profile.getDefaultModel())
                .status(profile.getStatus().name())
                .createdAt(profile.getCreatedAt())
                .updatedAt(profile.getUpdatedAt())
                .build();
    }
}
