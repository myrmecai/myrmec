package ai.myrmec.engine.agent.dto;

import ai.myrmec.engine.agent.AgentInstance;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Response containing information about an agent instance.
 */
@Data
@Builder
public class AgentInfoResponse {

    private UUID instanceId;
    private UUID agentId;
    private String hostname;
    private String ipAddress;
    private String runtimeVersion;
    private String status;
    private Instant registeredAt;
    private Instant lastHeartbeatAt;
    private Map<String, Object> metadata;

    public static AgentInfoResponse from(AgentInstance instance) {
        return AgentInfoResponse.builder()
                .instanceId(instance.getId())
                .agentId(instance.getAgentId())
                .hostname(instance.getHostname())
                .ipAddress(instance.getIpAddress())
                .runtimeVersion(instance.getRuntimeVersion())
                .status(instance.getStatus().name())
                .registeredAt(instance.getRegisteredAt())
                .lastHeartbeatAt(instance.getLastHeartbeatAt())
                .metadata(instance.getMetadata())
                .build();
    }
}
