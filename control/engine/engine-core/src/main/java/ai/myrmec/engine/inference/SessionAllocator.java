// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference;

import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.agent.AgentRepository;
import ai.myrmec.engine.node.NodeRegistryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Engine-owned session allocation core (protocol §7/§11). All allocation
 * state transitions happen under a pessimistic write lock on the live
 * instance row, so pending + active can never exceed the instance pool —
 * invariant #1 is structural, not advisory. One Agent row is minted at
 * session.opened (§19.1 decision 1) and released to IDLE on close.
 */
@Service
@Slf4j
public class SessionAllocator {

    public static final String ALLOC_STATE_OFFERED = "OFFERED";
    public static final String ALLOC_STATE_INITIALIZING = "INITIALIZING";
    public static final String ALLOC_STATE_ACTIVE = "ACTIVE";
    public static final String ALLOC_STATE_CLOSING = "CLOSING";
    public static final String ALLOC_STATE_CLOSED = "CLOSED";

    /** Statuses that consume a slot (§2.7): reusable + serving + not-yet-released. */
    private static final List<Agent.Status> SLOT_CONSUMING =
            List.of(Agent.Status.IDLE, Agent.Status.RESERVED, Agent.Status.BOUND, Agent.Status.DEAD);

    private final SessionRepository sessionRepository;
    private final AgentHostInstanceRepository instanceRepository;
    private final AgentRepository agentRepository;
    private final NodeRegistryService nodeRegistryService;

    private final int offerTimeoutSeconds;
    private final int idleTimeoutSeconds;
    private final boolean enabled;

    public SessionAllocator(
            SessionRepository sessionRepository,
            AgentHostInstanceRepository instanceRepository,
            AgentRepository agentRepository,
            NodeRegistryService nodeRegistryService,
            @Value("${myrmec.host.offer-timeout-seconds:10}") int offerTimeoutSeconds,
            @Value("${myrmec.host.session-idle-timeout-seconds:1800}") int idleTimeoutSeconds,
            @Value("${myrmec.host.allocation-sweep.enabled:true}") boolean enabled) {
        this.sessionRepository = sessionRepository;
        this.instanceRepository = instanceRepository;
        this.agentRepository = agentRepository;
        this.nodeRegistryService = nodeRegistryService;
        this.offerTimeoutSeconds = offerTimeoutSeconds;
        this.idleTimeoutSeconds = idleTimeoutSeconds;
        this.enabled = enabled;
    }

    /**
     * §7.1: atomically create a pending reservation. The session row is
     * minted here (OFFERED, lease set, instance pinned); the caller sends
     * the wire frame. Empty when the instance has no free capacity or is
     * not live.
     */
    @Transactional
    public Optional<UUID> offer(String kind, UUID refId, String serviceType,
                                  UUID projectId, UUID hostId) {
        AgentHostInstance instance = instanceRepository
                .findByAgentHostIdAndStatus(hostId, AgentHostInstance.Status.OPEN)
                .stream()
                .findFirst()
                .orElse(null);
        if (instance == null) {
            return Optional.empty();
        }

        // Lock the instance row — the atomic reservation point (§7.1).
        AgentHostInstance locked = instanceRepository.findWithLockById(instance.getId())
                .orElse(null);
        if (locked == null || locked.getStatus() != AgentHostInstance.Status.OPEN) {
            return Optional.empty();
        }

        long consuming = sessionRepository.countByHostInstanceIdAndAllocationStateIn(
                locked.getId(), List.of(ALLOC_STATE_OFFERED, ALLOC_STATE_INITIALIZING, ALLOC_STATE_ACTIVE));
        if (consuming >= locked.getPoolSize()) {
            log.debug("No capacity on instance {} (pool {}, consuming {})",
                    locked.getId(), locked.getPoolSize(), consuming);
            return Optional.empty();
        }

        Session session = new Session();
        session.setServiceType(serviceType);
        session.setRefId(refId);
        session.setProjectId(projectId);
        session.setKind(kind);
        session.setHostInstanceId(locked.getId());
        session.setAllocationState(ALLOC_STATE_OFFERED);
        session.setOfferExpiresAt(Instant.now().plus(Duration.ofSeconds(offerTimeoutSeconds)));
        // The legacy status column stays ACTIVE for context-assembly compat.
        session.setStatus("ACTIVE");
        session = sessionRepository.saveAndFlush(session);
        log.info("Offered session {} on instance {} (kind {})", session.getId(), locked.getId(), kind);
        return Optional.of(session.getId());
    }

    /** §7.2: host committed a local slot — OFFERED -> INITIALIZING. */
    @Transactional
    public boolean accept(UUID sessionId) {
        Session session = sessionRepository.findWithLockById(sessionId).orElse(null);
        if (session == null || !ALLOC_STATE_OFFERED.equals(session.getAllocationState())) {
            return false;
        }
        session.setAllocationState(ALLOC_STATE_INITIALIZING);
        sessionRepository.save(session);
        return true;
    }

    /** §7.2: host refused — reservation released (OFFERED -> CLOSED). */
    @Transactional
    public void reject(UUID sessionId, String reasonCode, String message, boolean retryable) {
        Session session = sessionRepository.findWithLockById(sessionId).orElse(null);
        if (session == null || !ALLOC_STATE_OFFERED.equals(session.getAllocationState())) {
            return;
        }
        session.setAllocationState(ALLOC_STATE_CLOSED);
        session.setClosedAt(Instant.now());
        sessionRepository.save(session);
        log.info("Session {} rejected ({}: {})", sessionId, reasonCode, message);
    }

    /**
     * §19.1 decision 1: the host confirmed context install — mint exactly one
     * Agent row for the serving slot, flip ACTIVE, start the idle lease.
     */
    @Transactional
    public boolean confirmOpened(UUID sessionId, String slotHostname) {
        Session session = sessionRepository.findWithLockById(sessionId).orElse(null);
        if (session == null || !ALLOC_STATE_INITIALIZING.equals(session.getAllocationState())) {
            // Idempotent replay: an already-ACTIVE session is a no-op success.
            return session != null && ALLOC_STATE_ACTIVE.equals(session.getAllocationState());
        }
        AgentHostInstance instance = instanceRepository.findById(session.getHostInstanceId())
                .orElse(null);
        if (instance == null) {
            return false;
        }

        // Mint the worker row (§19.1): IDLE reusable, stamped to the live run.
        if (agentRepository.countByAgentHostInstanceIdAndStatusIn(
                instance.getId(), List.of(Agent.Status.IDLE, Agent.Status.RESERVED, Agent.Status.BOUND)) == 0
                || agentRepository.findByAgentHostId(instance.getAgentHostId()).stream()
                        .noneMatch(a -> session.getRefId().equals(a.getConversationId()))) {
            Agent worker = new Agent();
            worker.setAgentHostId(instance.getAgentHostId());
            worker.setAgentHostInstanceId(instance.getId());
            worker.setStatus(Agent.Status.IDLE);
            worker.setHostname(slotHostname != null ? slotHostname : "host-slot");
            worker.setConversationId(session.getRefId());
            agentRepository.save(worker);
        }

        session.setAllocationState(ALLOC_STATE_ACTIVE);
        session.setIdleLeaseExpiresAt(Instant.now().plus(Duration.ofSeconds(idleTimeoutSeconds)));
        sessionRepository.save(session);
        log.info("Session {} ACTIVE on instance {}; worker minted", sessionId, instance.getId());
        return true;
    }

    /** §9: end the session and return its slot exactly once. */
    @Transactional
    public void close(UUID sessionId, String reasonCode) {
        Session session = sessionRepository.findWithLockById(sessionId).orElse(null);
        if (session == null || ALLOC_STATE_CLOSED.equals(session.getAllocationState())) {
            return; // idempotent
        }
        UUID refId = session.getRefId();
        session.setAllocationState(ALLOC_STATE_CLOSING);
        session.setAllocationState(ALLOC_STATE_CLOSED);
        session.setClosedAt(Instant.now());
        sessionRepository.save(session);

        // Release the serving worker (IDLE = reusable) — capacity returns.
        agentRepository.findByAgentHostIdAndStatus(instanceHostId(session), Agent.Status.IDLE)
                .stream()
                .filter(a -> refId.equals(a.getConversationId()))
                .findFirst()
                .ifPresent(worker -> {
                    worker.setConversationId(null);
                    agentRepository.save(worker);
                });
        log.info("Session {} closed ({})", sessionId, reasonCode);
    }

    /** §12.2 offer-expiry sweep body. */
    @Transactional
    public int expireOffers(Instant now) {
        List<Session> stale = sessionRepository.findByAllocationStateAndOfferExpiresAtBefore(
                ALLOC_STATE_OFFERED, now);
        for (Session session : stale) {
            session.setAllocationState(ALLOC_STATE_CLOSED);
            session.setClosedAt(Instant.now());
            sessionRepository.save(session);
        }
        return stale.size();
    }

    /** §12.2 idle-lease sweep body. */
    @Transactional
    public int expireIdleLeases(Instant now) {
        List<Session> stale = sessionRepository.findByAllocationStateAndIdleLeaseExpiresAtBefore(
                ALLOC_STATE_ACTIVE, now);
        for (Session session : stale) {
            close(session.getId(), "IDLE_LEASE_EXPIRED");
        }
        return stale.size();
    }

    /** §12.2 scheduled sweep — disabled in e2e like the reaper. */
    @Scheduled(fixedDelayString = "${myrmec.host.allocation-sweep.interval-ms:15000}")
    public void sweepAllocation() {
        if (!enabled) {
            return;
        }
        int expiredOffers = expireOffers(Instant.now());
        int expiredLeases = expireIdleLeases(Instant.now());
        if (expiredOffers + expiredLeases > 0) {
            log.info("Allocation sweep: {} offers, {} leases expired", expiredOffers, expiredLeases);
        }
    }

    private UUID instanceHostId(Session session) {
        return instanceRepository.findById(session.getHostInstanceId())
                .map(AgentHostInstance::getAgentHostId)
                .orElse(null);
    }
}
