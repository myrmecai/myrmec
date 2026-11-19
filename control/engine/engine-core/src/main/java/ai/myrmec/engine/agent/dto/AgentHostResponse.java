// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent.dto;

import ai.myrmec.engine.agent.AgentHost;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Response DTO for agent host data.
 */
@Data
@Builder
public class AgentHostResponse {

    private UUID id;
    private String name;
    private String description;
    private UUID teamId;
    private String teamName;
    private Integer maxAgents;
    private AgentHost.Status status;
    private ai.myrmec.engine.agent.AgentHostType hostType;
    private ai.myrmec.engine.agent.ModelAccessMode modelAccessMode;
    private int activeInstanceCount;
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * Convert entity to response DTO.
     */
    public static AgentHostResponse from(AgentHost agentHost) {
        return from(agentHost, null, 0);
    }

    /**
     * Convert entity to response DTO with Team name.
     */
    public static AgentHostResponse from(AgentHost agentHost, String teamName, int activeInstanceCount) {
        return AgentHostResponse.builder()
                .id(agentHost.getId())
                .name(agentHost.getName())
                .description(agentHost.getDescription())
                .teamId(agentHost.getTeamId())
                .teamName(teamName)
                .maxAgents(agentHost.getMaxAgents())
                .status(agentHost.getStatus())
                .hostType(agentHost.getHostType())
                .modelAccessMode(agentHost.getModelAccessMode())
                .activeInstanceCount(activeInstanceCount)
                .createdAt(agentHost.getCreatedAt())
                .updatedAt(agentHost.getUpdatedAt())
                .build();
    }
}
