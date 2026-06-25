package ai.myrmec.engine.agent.health;

import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentRepository;
import ai.myrmec.engine.websocket.AgentConnectionManager;
import ai.myrmec.engine.workflow.AttemptStatus;
import ai.myrmec.engine.workflow.TaskAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Phase 9d — assembles {@link AgentHealthSnapshot} from
 * {@link Agent} rows + the in-process
 * {@link AgentConnectionManager} idle/busy state + the queue depth
 * read from {@code task_attempts}.
 *
 * <p>Heartbeat freshness threshold is {@code stale} when the last
 * heartbeat is older than {@code 2 ×}
 * {@code myrmec.agent.heartbeat.interval-seconds} (default 30 →
 * stale at 60s). Tunable so deploys with longer heartbeat intervals
 * don't false-positive the UI.</p>
 */
@Service
@RequiredArgsConstructor
public class AgentHealthService {

    private final AgentRepository instanceRepository;
    private final AgentConnectionManager connectionManager;
    private final TaskAttemptRepository taskAttemptRepository;

    @Value("${myrmec.agent.heartbeat.interval-seconds:30}")
    private long heartbeatIntervalSeconds;

    public AgentHealthSnapshot snapshot(UUID agentId) {
        List<Agent> instances = instanceRepository.findByAgentHostId(agentId);
        Instant now = Instant.now();
        long staleAfterSeconds = Math.max(2 * heartbeatIntervalSeconds, 5);

        int total = instances.size();
        int online = 0;
        int idle = 0;
        int busy = 0;
        int stale = 0;
        long queueDepth = 0;
        Instant latest = null;
        List<AgentHealthSnapshot.InstanceHealth> rows = new ArrayList<>(total);

        for (Agent ai : instances) {
            boolean isOnline = ai.getStatus() == Agent.Status.IDLE;
            if (isOnline) {
                online++;
            }
            boolean isIdle = isOnline && connectionManager.isAgentIdle(ai.getId());
            if (isOnline) {
                if (isIdle) {
                    idle++;
                } else {
                    busy++;
                }
            }

            Instant hb = ai.getLastHeartbeatAt();
            if (hb != null && (latest == null || hb.isAfter(latest))) {
                latest = hb;
            }
            Long secondsSince = hb == null ? null : Duration.between(hb, now).getSeconds();
            boolean isStale = isOnline && (hb == null || secondsSince > staleAfterSeconds);
            if (isStale) {
                stale++;
            }

            long active = taskAttemptRepository
                    .countByAgentInstanceIdAndStatus(ai.getId(), AttemptStatus.RUNNING);
            queueDepth += active;

            rows.add(AgentHealthSnapshot.InstanceHealth.builder()
                    .instanceId(ai.getId())
                    .hostname(ai.getHostname())
                    .runtimeVersion(ai.getRuntimeVersion())
                    .status(ai.getStatus().name())
                    .idle(isIdle)
                    .stale(isStale)
                    .registeredAt(ai.getRegisteredAt())
                    .lastHeartbeatAt(hb)
                    .secondsSinceHeartbeat(secondsSince)
                    .activeAttempts(active)
                    .build());
        }

        return AgentHealthSnapshot.builder()
                .agentId(agentId)
                .totalInstances(total)
                .onlineInstances(online)
                .idleInstances(idle)
                .busyInstances(busy)
                .staleInstances(stale)
                .queueDepth(queueDepth)
                .latestHeartbeatAt(latest)
                .instances(rows)
                .build();
    }
}
