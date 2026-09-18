// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host;

import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.inference.SessionAllocator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.time.Instant;

/**
 * §12.2 "mark unavailable → begin reconciliation": a host instance whose
 * control socket has gone silent — status still OPEN but no
 * {@code host.heartbeat} inside the staleness window — is a zombie: its
 * engine-side row claims liveness the transport no longer has. The sweeper
 * closes the zombie socket, which fires the EXISTING recovery entry
 * ({@code afterConnectionClosed → startRecovery}) and parks the instance in
 * the bounded RECOVERING window; the retention sweep owns expiry from there.
 *
 * <p><b>Window base (documented simplification):</b> the host's negotiated
 * heartbeat interval rides {@code host.opened} but is NOT persisted on the
 * instance row, so the per-instance interval is unknowable here. The sweeper
 * therefore uses the engine's {@code myrmec.host.heartbeat-interval-seconds}
 * configuration (the same base the engine advertises at open) as the floor:
 * {@code stalenessAfter = factor × heartbeatIntervalSeconds} (defaults
 * 2 × 15s = 30s). Hosts negotiating a longer interval than the engine
 * default may see an occasional early close; the recovery window makes that
 * survivable (resume re-adopts the instance).</p>
 */
@Service
@Slf4j
public class HostHeartbeatStalenessSweeper {

    private final AgentHostInstanceRepository instanceRepository;
    private final HostConnectionManager connectionManager;
    private final SessionAllocator sessionAllocator;
    private final boolean enabled;
    private final java.time.Duration stalenessAfter;

    public HostHeartbeatStalenessSweeper(
            AgentHostInstanceRepository instanceRepository,
            HostConnectionManager connectionManager,
            SessionAllocator sessionAllocator,
            @Value("${myrmec.host.heartbeat-staleness.enabled:true}") boolean enabled,
            @Value("${myrmec.host.heartbeat-staleness.factor:2}") int factor,
            @Value("${myrmec.host.heartbeat-interval-seconds:15}") int heartbeatIntervalSeconds) {
        this.instanceRepository = instanceRepository;
        this.connectionManager = connectionManager;
        this.sessionAllocator = sessionAllocator;
        this.enabled = enabled;
        this.stalenessAfter = java.time.Duration.ofSeconds(
                (long) factor * heartbeatIntervalSeconds);
    }

    /** Scheduled entry point — disabled in the e2e profile like the reaper. */
    @Scheduled(fixedDelayString = "${myrmec.host.heartbeat-staleness.interval-ms:15000}")
    public void scheduled() {
        if (!enabled) {
            return;
        }
        sweep(Instant.now());
    }

    /**
     * Close the socket of every OPEN instance whose last liveness signal
     * predates the staleness window; returns rows acted on.
     *
     * <p><b>Ordering:</b> the socket close comes first — Spring fires
     * {@code afterConnectionClosed} from it, and the close path owns the
     * RECOVERING transition. The sweeper then arms {@code startRecovery}
     * directly as a safety net: {@code startRecovery} is idempotent and never
     * shortens an already-armed window, so the two paths converge whether the
     * close callback ran or the socket was already dead. A socket-less OPEN
     * row (shouldn't exist, but be safe) gets the direct arm only.</p>
     */
    @Transactional
    public int sweep(Instant now) {
        Instant cutoff = now.minus(stalenessAfter);
        var stale = new java.util.ArrayList<AgentHostInstance>();
        stale.addAll(instanceRepository.findByStatusAndLastHeartbeatAtBefore(
                AgentHostInstance.Status.OPEN, cutoff));
        // Instances that never signalled liveness: use openedAt as the
        // reference so a host opened moments ago isn't killed before its
        // first heartbeat is due (grace = stalenessAfter from openedAt).
        instanceRepository.findByStatusAndLastHeartbeatAtIsNull(
                AgentHostInstance.Status.OPEN).stream()
                .filter(i -> i.getOpenedAt() != null && i.getOpenedAt().isBefore(cutoff))
                .forEach(stale::add);

        for (AgentHostInstance staleInstance : stale) {
            log.warn("Host instance {} stale — no heartbeat since {} (window {}s); "
                            + "closing zombie socket to begin recovery",
                    staleInstance.getId(),
                    staleInstance.getLastHeartbeatAt() != null
                            ? staleInstance.getLastHeartbeatAt() : staleInstance.getOpenedAt(),
                    stalenessAfter.toSeconds());
            instanceRepository.findById(staleInstance.getId()).ifPresent(instance -> {
                closeZombieSocket(instance.getId());
                // Idempotent safety net: arms the window when the close path
                // didn't (socket already dead / close callback not yet run).
                instance.startRecovery(sessionAllocator.recoveryRetainFor());
                instanceRepository.save(instance);
            });
        }
        return stale.size();
    }

    /**
     * Force-close a zombie control socket. {@code WebSocketSession.close}
     * triggers the container's connection-close bookkeeping, which lands in
     * the host handler's {@code afterConnectionClosed} — the existing
     * recovery entry point. The socket may already be dead: every failure is
     * swallowed (the direct recovery arm below covers it).
     */
    private void closeZombieSocket(java.util.UUID instanceId) {
        java.util.Optional<WebSocketSession> socket = connectionManager.getSession(instanceId);
        if (socket.isEmpty()) {
            return;
        }
        try {
            socket.get().close(CloseStatus.GOING_AWAY);
        } catch (Exception e) {
            log.debug("Failed closing zombie socket of instance {}: {}",
                    instanceId, e.getMessage());
        }
    }
}