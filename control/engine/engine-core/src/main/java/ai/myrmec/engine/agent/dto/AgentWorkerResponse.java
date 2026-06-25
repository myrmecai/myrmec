package ai.myrmec.engine.agent.dto;

import ai.myrmec.engine.agent.Agent;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model for one ephemeral worker replica ({@link Agent}) of an agent
 * host. Surfaces the runtime worker FSM status
 * (IDLE/RESERVED/CONNECTING/BOUND/DRAINING/DEAD), the conversation it is
 * currently serving (if any), and the heartbeat/state clocks operators need
 * to diagnose stuck or lost workers (agent-concurrency §9.5).
 */
public record AgentWorkerResponse(
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
    public static AgentWorkerResponse from(Agent worker) {
        return new AgentWorkerResponse(
                worker.getId(),
                worker.getAgentHostId(),
                worker.getConversationId(),
                worker.getHostname(),
                worker.getIpAddress(),
                worker.getRuntimeVersion(),
                worker.getStatus() == null ? null : worker.getStatus().name(),
                worker.getRegisteredAt(),
                worker.getLastHeartbeatAt(),
                worker.getStateChangedAt()
        );
    }
}
