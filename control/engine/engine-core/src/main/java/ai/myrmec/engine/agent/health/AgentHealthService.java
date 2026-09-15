package ai.myrmec.engine.agent.health;

import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentRepository;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.inference.SessionAllocator;
import ai.myrmec.engine.inference.SessionRepository;
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
 * {@link Agent} rows + the live host-instance/sessions allocation
 * state + the queue depth read from {@code task_attempts}.
 *
 * <p>Since the legacy agent wire was deleted (P6-T6), a worker is
 * "online" when its host has a live OPEN instance, "idle" when the
 * worker row is IDLE with no ACTIVE session, and "busy" when it serves
 * an ACTIVE/INITIALIZING session. Heartbeat freshness threshold is
 * {@code stale} when the last heartbeat is older than {@code 2 ×}
 * {@code myrmec.agent.heartbeat.interval-seconds} (default 30 →
 * stale at 60s). Tunable so deploys with longer heartbeat intervals
 * don't false-positive the UI.</p>
 */
@Service
@RequiredArgsConstructor
public class AgentHealthService {

    private final AgentRepository instanceRepository;
    private final AgentHostInstanceRepository hostInstanceRepository;
    private final SessionRepository sessionRepository;
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
            boolean isOnline = hostInstanceRepository
                    .findByAgentHostIdAndStatus(ai.getAgentHostId(),
                            AgentHostInstance.Status.OPEN)
                    .stream().anyMatch(inst -> ai.getAgentHostInstanceId() != null
                            && ai.getAgentHostInstanceId().equals(inst.getId()));
            if (isOnline) {
                online++;
            }
            // A worker is busy when it serves a not-yet-closed session; idle
            // when its row is IDLE and nothing is allocated on it.
            boolean isBusy = isOnline && ai.getId() != null
                    && ai.getAgentHostInstanceId() != null
                    && sessionRepository.countByHostInstanceIdAndAllocationStateIn(
                            ai.getAgentHostInstanceId(),
                            java.util.List.of(SessionAllocator.ALLOC_STATE_OFFERED,
                                    SessionAllocator.ALLOC_STATE_INITIALIZING,
                                    SessionAllocator.ALLOC_STATE_ACTIVE)) > 0;
            boolean isIdle = isOnline && !isBusy;
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
