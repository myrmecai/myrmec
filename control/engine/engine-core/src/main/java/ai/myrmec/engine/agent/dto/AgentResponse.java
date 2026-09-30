package ai.myrmec.engine.agent.dto;

import ai.myrmec.engine.agent.Agent;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model for one {@link Agent} — the session-bound execution body an
 * agent host serves runs with. Surfaces the runtime Agent FSM status
 * (IDLE/RESERVED/CONNECTING/BOUND/DRAINING/DEAD), the conversation it is
 * currently serving (if any), and the heartbeat/state clocks operators need
 * to diagnose stuck or lost Agents (agent-concurrency §9.5).
 */
public record AgentResponse(
        UUID id,
        UUID agentHostId,
        UUID conversationId,
        String hostname,
        String ipAddress,
        String runtimeVersion,
        String status,
        Instant registeredAt,
        Instant lastHeartbeatAt,
        Instant stateChangedAt
) {
    public static AgentResponse from(Agent agent) {
        return new AgentResponse(
                agent.getId(),
                agent.getAgentHostId(),
                agent.getConversationId(),
                agent.getHostname(),
                agent.getIpAddress(),
                agent.getRuntimeVersion(),
                agent.getStatus() == null ? null : agent.getStatus().name(),
                agent.getRegisteredAt(),
                agent.getLastHeartbeatAt(),
                agent.getStateChangedAt()
        );
    }
}
