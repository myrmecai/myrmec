package ai.myrmec.engine.agent;

import ai.myrmec.engine.conversation.ConversationEventReason;
import ai.myrmec.engine.conversation.ConversationEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Slice 4d — warm-worker FSM reaper (agent-concurrency §9.5).
 *
 * <p>A worker can stall in a transient binding state if the Agent Host never
 * acks an {@code agent.bind}, never opens its conversation socket, or simply
 * dies mid-binding. Three timers reclaim those workers so the warm pool does
 * not leak capacity:</p>
 *
 * <ul>
 *   <li><b>RESERVE_TIMEOUT</b> — a worker still {@code RESERVED} past the
 *       cutoff (the host never acked the bind) is released back to
 *       {@code IDLE} for re-dispatch.</li>
 *   <li><b>CONNECT_TIMEOUT</b> — a worker still {@code CONNECTING} past the
 *       cutoff (the host acked but its conversation socket never attached) is
 *       released back to {@code IDLE}.</li>
 *   <li><b>HOST_LOST</b> — any mid-lifecycle worker
 *       ({@code RESERVED}/{@code CONNECTING}/{@code BOUND}/{@code DRAINING})
 *       whose heartbeat has gone stale is flipped to {@code DEAD} (its host
 *       is gone, not merely slow).</li>
 * </ul>
 *
 * <p>HOST_LOST runs first: a worker that is both heartbeat-stale and
 * reserve-timed-out belongs to a dead host, so {@code DEAD} (not the
 * reclaim-to-pool {@code IDLE}) is the correct terminal state, and once it is
 * {@code DEAD} the transient-timeout queries no longer match it.</p>
 *
 * <p>Disabled in the e2e profile via {@code myrmec.agent.reaper.enabled} so a
 * shared in-memory context does not asynchronously yank workers that a test
 * is driving by hand.</p>
 */
@Service
@Slf4j
public class AgentReaperService {

    /** The transient/active states a HOST_LOST sweep flips to DEAD. */
    private static final List<Agent.Status> MID_LIFECYCLE = List.of(
            Agent.Status.RESERVED,
            Agent.Status.CONNECTING,
            Agent.Status.BOUND,
            Agent.Status.DRAINING);

    private final AgentRepository agentRepository;
    private final ConversationEventService conversationEventService;
    private final boolean enabled;
    private final long reserveTimeoutMs;
    private final long connectTimeoutMs;
    private final long hostLostThresholdMs;

    public AgentReaperService(
            AgentRepository agentRepository,
            ConversationEventService conversationEventService,
            @Value("${myrmec.agent.reaper.enabled:true}") boolean enabled,
            @Value("${myrmec.agent.reaper.reserve-timeout-ms:10000}") long reserveTimeoutMs,
            @Value("${myrmec.agent.reaper.connect-timeout-ms:15000}") long connectTimeoutMs,
            @Value("${myrmec.agent.reaper.host-lost-threshold-ms:70000}") long hostLostThresholdMs) {
        this.agentRepository = agentRepository;
        this.conversationEventService = conversationEventService;
        this.enabled = enabled;
        this.reserveTimeoutMs = reserveTimeoutMs;
        this.connectTimeoutMs = connectTimeoutMs;
        this.hostLostThresholdMs = hostLostThresholdMs;
    }

    @Scheduled(fixedRateString = "${myrmec.agent.reaper.interval-ms:5000}")
    @Transactional
    public void reapAgents() {
        if (!enabled) {
            return;
        }
        Instant now = Instant.now();
        reapHostLost(now);
        reapTransient(now, Agent.Status.RESERVED, reserveTimeoutMs, ConversationEventReason.RESERVE_TIMEOUT);
        reapTransient(now, Agent.Status.CONNECTING, connectTimeoutMs, ConversationEventReason.CONNECT_TIMEOUT);
    }

    /**
     * HOST_LOST — mid-lifecycle workers whose heartbeat went stale belong to
     * a dead host; flip them to {@code DEAD}.
     */
    void reapHostLost(Instant now) {
        Instant cutoff = now.minusMillis(hostLostThresholdMs);
        List<Agent> stale = agentRepository.findStaleByStatusIn(cutoff, MID_LIFECYCLE);
        if (stale.isEmpty()) {
            return;
        }
        for (Agent worker : stale) {
            log.warn("HOST_LOST: agent {} ({}) heartbeat stale since {} -> DEAD",
                    worker.getId(), worker.getStatus(), worker.getLastHeartbeatAt());
            UUID conversationId = worker.getConversationId();
            Agent.Status fromState = worker.getStatus();
            worker.markOffline();
            conversationEventService.record(conversationId, worker.getId(), worker.getAgentHostId(),
                    fromState, Agent.Status.DEAD, ConversationEventReason.HOST_LOST);
        }
        agentRepository.saveAll(stale);
    }

    /**
     * RESERVE_TIMEOUT / CONNECT_TIMEOUT — workers stuck in a transient
     * binding state past the cutoff are released back into the warm pool.
     */
    void reapTransient(Instant now, Agent.Status status, long timeoutMs, ConversationEventReason reason) {
        Instant cutoff = now.minusMillis(timeoutMs);
        List<Agent> stuck = agentRepository.findByStatusAndStateChangedAtBefore(status, cutoff);
        if (stuck.isEmpty()) {
            return;
        }
        for (Agent worker : stuck) {
            log.warn("{}: agent {} stuck {} since {} -> IDLE",
                    reason, worker.getId(), status, worker.getStateChangedAt());
            UUID conversationId = worker.getConversationId();
            UUID hostId = worker.getAgentHostId();
            worker.release();
            conversationEventService.record(conversationId, worker.getId(), hostId,
                    status, Agent.Status.IDLE, reason);
        }
        agentRepository.saveAll(stuck);
    }
}
