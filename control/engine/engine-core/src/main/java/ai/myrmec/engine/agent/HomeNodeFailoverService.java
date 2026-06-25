// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

import ai.myrmec.engine.conversation.ConversationEventReason;
import ai.myrmec.engine.conversation.ConversationEventService;
import ai.myrmec.engine.node.EngineNodeRepository;
import ai.myrmec.engine.node.NodeRegistryService;
import ai.myrmec.engine.websocket.AgentWebSocketHandler;
import ai.myrmec.engine.websocket.message.payload.AgentBindPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Slice #106a — conversation-socket home-node failover sweep
 * (agent-concurrency §9.11).
 *
 * <p>The bind path (§9.4) pins a {@code BOUND} worker's conversation socket to
 * a home replica ({@code agents.home_node_id}). When that replica dies the
 * node-registry reaper (§9.7) marks it {@code DOWN}; this sweep then re-homes
 * the workers that were serving turns on it — but only the ones whose
 * <i>agent host</i> is still alive, so the two recovery owners stay disjoint:</p>
 *
 * <ul>
 *   <li><b>Home node {@code DOWN}, host alive</b> — owned here. The worker is
 *       re-homed to a live replica: {@code markReHoming()} ({@code BOUND →
 *       CONNECTING}, {@code home_node_id} cleared), a {@code HOME_NODE_LOST}
 *       event is recorded, and {@code agent.bind} is re-dispatched through the
 *       existing path with this replica as the new home. The Agent re-attaches
 *       and the standard {@code INSTANCE_BOUND} fires with the new home node.</li>
 *   <li><b>Home node DOWN, host also gone</b> — <i>left</i> for the
 *       {@code HOST_LOST} reaper (the single owner of {@code * → DEAD},
 *       {@link AgentReaperService}). A worker whose control-socket heartbeat
 *       has gone stale belongs to a dead host, so re-homing it would be
 *       pointless; it must die, not re-bind.</li>
 *   <li><b>Home node UP</b> — not our concern. A transient socket blip is
 *       recovered by the Agent Supervisor reconnecting to the <i>same</i> node
 *       (CONVERSATION_REATTACHED); this sweep never touches an UP home.</li>
 * </ul>
 *
 * <p>"Host alive" is read from the worker's heartbeat: a worker whose
 * {@code last_heartbeat_at} is within {@code host-lost-threshold-ms} still has
 * a live control socket; once it goes stale the {@code HOST_LOST} reaper owns
 * it. This is the same threshold the reaper uses, so the two sweeps partition
 * the population cleanly and never both act on one worker.</p>
 *
 * <p>Disabled in the e2e profile via {@code myrmec.agent.failover.enabled} (as
 * {@link AgentReaperService} is) so an async timer does not re-home workers a
 * test is driving by hand.</p>
 */
@Service
@Slf4j
public class HomeNodeFailoverService {

    /** Statuses whose home replica we re-home when it goes {@code DOWN}. */
    private static final List<Agent.Status> RE_HOMABLE = List.of(
            Agent.Status.BOUND,
            Agent.Status.CONNECTING);

    private final AgentRepository agentRepository;
    private final EngineNodeRepository nodeRepository;
    private final ConversationEventService conversationEventService;
    private final AgentWebSocketHandler webSocketHandler;
    private final NodeRegistryService nodeRegistry;
    private final boolean enabled;
    private final long hostLostThresholdMs;

    public HomeNodeFailoverService(
            AgentRepository agentRepository,
            EngineNodeRepository nodeRepository,
            ConversationEventService conversationEventService,
            AgentWebSocketHandler webSocketHandler,
            NodeRegistryService nodeRegistry,
            @Value("${myrmec.agent.failover.enabled:true}") boolean enabled,
            @Value("${myrmec.agent.reaper.host-lost-threshold-ms:70000}") long hostLostThresholdMs) {
        this.agentRepository = agentRepository;
        this.nodeRepository = nodeRepository;
        this.conversationEventService = conversationEventService;
        this.webSocketHandler = webSocketHandler;
        this.nodeRegistry = nodeRegistry;
        this.enabled = enabled;
        this.hostLostThresholdMs = hostLostThresholdMs;
    }

    @Scheduled(fixedRateString = "${myrmec.agent.failover.interval-ms:5000}")
    @Transactional
    public void sweep() {
        if (!enabled) {
            return;
        }
        reHomeLostWorkers(Instant.now());
    }

    /**
     * Re-home every {@code BOUND}/{@code CONNECTING} worker whose home replica
     * is {@code DOWN} but whose agent host is still heartbeating. Package-private
     * so the test can drive it directly (the timer is off under e2e).
     */
    void reHomeLostWorkers(Instant now) {
        List<String> downNodeIds = nodeRepository.findDownNodeIds();
        if (downNodeIds.isEmpty()) {
            return;
        }
        List<Agent> orphans = agentRepository.findByStatusInAndHomeNodeIdIn(RE_HOMABLE, downNodeIds);
        if (orphans.isEmpty()) {
            return;
        }
        Instant hostLostCutoff = now.minusMillis(hostLostThresholdMs);
        for (Agent worker : orphans) {
            // Host also gone? Leave it for the HOST_LOST reaper (single owner of
            // * → DEAD). Re-homing a worker on a dead host would never attach.
            if (worker.getLastHeartbeatAt() == null
                    || worker.getLastHeartbeatAt().isBefore(hostLostCutoff)) {
                log.debug("Worker {} home node {} is DOWN but host heartbeat is stale "
                        + "({}); leaving for HOST_LOST reaper",
                        worker.getId(), worker.getHomeNodeId(), worker.getLastHeartbeatAt());
                continue;
            }

            String lostHomeNode = worker.getHomeNodeId();
            Agent.Status fromState = worker.getStatus();
            java.util.UUID conversationId = worker.getConversationId();
            java.util.UUID hostId = worker.getAgentHostId();
            java.util.UUID profileVersionId = worker.getProfileVersionId();

            worker.markReHoming();
            agentRepository.save(worker);
            conversationEventService.record(conversationId, worker.getId(), hostId,
                    fromState, Agent.Status.CONNECTING, ConversationEventReason.HOME_NODE_LOST);
            log.warn("HOME_NODE_LOST: agent {} home node {} DOWN -> re-homing to {} (conv {})",
                    worker.getId(), lostHomeNode, nodeRegistry.getSelfNodeId(), conversationId);

            // Re-dispatch agent.bind via the existing path with THIS replica as
            // the new home. markReHoming() cleared home_node_id, so the control
            // frame routes to the worker's (live) host control socket.
            webSocketHandler.sendAgentBind(worker.getId(),
                    AgentBindPayload.builder()
                            .conversationId(conversationId)
                            .profileVersionId(profileVersionId)
                            .homeNodeId(nodeRegistry.getSelfNodeId())
                            .homeNodeAddr(nodeRegistry.getSelfAddress())
                            .build());
        }
    }
}
