package ai.myrmec.engine.node;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Slice 4a — engine replica self-registration, heartbeat and reaper
 * (agent-concurrency §9.7/§9.8).
 *
 * <p>On startup this replica writes (or refreshes) its own
 * {@link EngineNode} row as {@code UP}. A timer heartbeats the row so peers
 * can tell it is alive; a second timer reaps peers whose heartbeat has gone
 * stale, marking them {@code DOWN}. On a single node this is a one-row table
 * and the reaper never fires (a node never reaps itself).</p>
 *
 * <p>Reaping the <i>bindings</i> owned by a dead replica (agents → DEAD,
 * host control sockets, in-flight conversations) is a separate concern
 * handled by the HOST_LOST reaper in slice 4d; 4a only maintains the
 * registry itself.</p>
 */
@Service
@Slf4j
public class NodeRegistryService {

    private final EngineNodeRepository nodeRepository;

    /** Stable per-replica id — k8s pod name in production, hostname in dev. */
    private final String selfNodeId;
    /** In-cluster address peers dial this replica at (pod address, not VIP). */
    private final String selfAddress;
    private final boolean enabled;
    private final long staleThresholdMs;

    public NodeRegistryService(
            EngineNodeRepository nodeRepository,
            @Value("${myrmec.node.id:}") String configuredNodeId,
            @Value("${myrmec.node.address:}") String configuredAddress,
            @Value("${server.port:9090}") int serverPort,
            @Value("${myrmec.node.registry.enabled:true}") boolean enabled,
            @Value("${myrmec.node.registry.stale-threshold-ms:30000}") long staleThresholdMs) {
        this.nodeRepository = nodeRepository;
        this.enabled = enabled;
        this.staleThresholdMs = staleThresholdMs;
        String host = resolveHostname();
        this.selfNodeId = configuredNodeId.isBlank() ? host : configuredNodeId;
        this.selfAddress = configuredAddress.isBlank() ? host + ":" + serverPort : configuredAddress;
    }

    /** This replica's stable id — owner pointers on agents/hosts/conversations carry it. */
    public String getSelfNodeId() {
        return selfNodeId;
    }

    /** This replica's in-cluster dial address. */
    public String getSelfAddress() {
        return selfAddress;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void registerSelf() {
        // Always register self so the engine_nodes row exists for FK
        // constraints (agents.home_node_id → engine_nodes.node_id). The
        // `enabled` flag gates only the heartbeat/reaper timers, not the
        // initial registration — without the row, attachConversation throws
        // a FK violation when it sets home_node_id on the agent instance.
        Instant now = Instant.now();
        EngineNode self = nodeRepository.findById(selfNodeId).orElseGet(() -> {
            EngineNode node = new EngineNode();
            node.setNodeId(selfNodeId);
            node.setStartedAt(now);
            return node;
        });
        self.setAddress(selfAddress);
        self.setStatus(EngineNode.Status.UP);
        self.setStartedAt(now);
        self.setLastHeartbeatAt(now);
        nodeRepository.save(self);
        log.info("Engine node registered: id={} address={} (registry.enabled={})",
                selfNodeId, selfAddress, enabled);
    }

    @Scheduled(fixedRateString = "${myrmec.node.registry.heartbeat-interval-ms:10000}")
    @Transactional
    public void heartbeat() {
        if (!enabled) {
            return;
        }
        int updated = nodeRepository.touchHeartbeat(selfNodeId, Instant.now());
        if (updated == 0) {
            // Row vanished (e.g. reaped after a long GC pause) — re-register.
            log.warn("Engine node {} heartbeat found no row; re-registering", selfNodeId);
            registerSelf();
        }
    }

    @Scheduled(fixedRateString = "${myrmec.node.registry.reaper-interval-ms:15000}")
    @Transactional
    public void reapStaleNodes() {
        if (!enabled) {
            return;
        }
        Instant cutoff = Instant.now().minus(staleThresholdMs, ChronoUnit.MILLIS);
        List<EngineNode> stale = nodeRepository.findStaleNodes(cutoff, selfNodeId);
        if (stale.isEmpty()) {
            return;
        }
        for (EngineNode node : stale) {
            node.setStatus(EngineNode.Status.DOWN);
            log.warn("Engine node {} marked DOWN (last heartbeat {})", node.getNodeId(), node.getLastHeartbeatAt());
        }
        nodeRepository.saveAll(stale);
    }

    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            String fallback = "node-" + Long.toHexString(System.nanoTime());
            log.warn("Could not resolve local hostname; using {}", fallback);
            return fallback;
        }
    }
}
