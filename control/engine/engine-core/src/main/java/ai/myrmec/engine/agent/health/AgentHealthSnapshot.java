package ai.myrmec.engine.agent.health;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phase 9d — read-side projection of agent runtime health for the
 * agent admin UI. Aggregates instance-level state (heartbeat freshness,
 * busy/idle, active task count) plus an agent-level roll-up.
 */
@Value
@Builder
public class AgentHealthSnapshot {

    UUID agentId;

    /** Total number of registered AgentInstance rows for this agent. */
    int totalInstances;

    /** Instances with status = ONLINE. */
    int onlineInstances;

    /** ONLINE instances whose connection is idle (no task currently). */
    int idleInstances;

    /** ONLINE instances currently executing a task. */
    int busyInstances;

    /** Instances flagged stale (no heartbeat for &gt;2× the heartbeat interval). */
    int staleInstances;

    /** Sum of active task attempts across all instances of this agent. */
    long queueDepth;

    /** Most-recent heartbeat across all instances; null when no instance exists. */
    Instant latestHeartbeatAt;

    /** Per-instance breakdown for the UI to render a table. */
    List<InstanceHealth> instances;

    @Value
    @Builder
    public static class InstanceHealth {
        UUID instanceId;
        String hostname;
        String runtimeVersion;
        String status; // ONLINE / OFFLINE / DRAINING (mirrors AgentInstance.Status)
        boolean idle;
        boolean stale;
        Instant registeredAt;
        Instant lastHeartbeatAt;
        Long secondsSinceHeartbeat;
        long activeAttempts;
    }
}
