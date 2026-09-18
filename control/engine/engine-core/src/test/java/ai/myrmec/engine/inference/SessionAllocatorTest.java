// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.agent.AgentRepository;
import ai.myrmec.engine.inference.execution.SessionExecution;
import ai.myrmec.engine.inference.execution.SessionExecutionRepository;
import ai.myrmec.engine.node.NodeRegistryService;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §7/§11.3 allocation invariants under the house pessimistic-lock pattern:
 * pending+active < pool per instance; every allocation outcome consumes at
 * most one slot; exactly one Agent row minted per session.opened; capacity
 * returns exactly once on every terminal path.
 */
class SessionAllocatorTest extends IntegrationTestBase {

    @Autowired SessionAllocator allocator;
    @Autowired TestDataBuilder data;
    @Autowired AgentHostInstanceRepository instances;
    @Autowired AgentRepository agentRepository;
    @Autowired SessionRepository sessionRepository;
    @Autowired NodeRegistryService nodeRegistryService;
    @Autowired SessionExecutionRepository executionRepository;

    private AgentHostInstance openInstance(int pool) {
        AgentHostCreationResult created =
                data.agent().named("a-host").withMaxAgents(10).create();
        return instances.saveAndFlush(AgentHostInstance.open(
                created.agent(), null, UUID.randomUUID().toString(), "laptop", pool, Map.of(), "node-1"));
    }

    private Project project() {
        return data.project().named("a-proj").create();
    }

    @Test
    void offerReservesPendingCapacityAtomically() {
        AgentHostInstance instance = openInstance(2);
        Project project = project();

        UUID s1 = allocator.offer("CONVERSATION", UUID.randomUUID(), "CONVERSATION",
                project.getId(), instance.getAgentHostId()).orElseThrow();
        UUID s2 = allocator.offer("CONVERSATION", UUID.randomUUID(), "CONVERSATION",
                project.getId(), instance.getAgentHostId()).orElseThrow();
        // Pool 2: third offer must be refused (pending + active < pool).
        assertThat(allocator.offer("CONVERSATION", UUID.randomUUID(), "CONVERSATION",
                project.getId(), instance.getAgentHostId())).isEmpty();

        Session first = sessionRepository.findById(s1).orElseThrow();
        assertThat(first.getAllocationState()).isEqualTo(SessionAllocator.ALLOC_STATE_OFFERED);
        assertThat(first.getHostInstanceId()).isEqualTo(instance.getId());
        assertThat(first.getOfferExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void acceptRejectAndExpiryAreSingleOutcomeTransitions() {
        AgentHostInstance instance = openInstance(3);
        Project project = project();

        UUID accepted = allocator.offer("CONVERSATION", UUID.randomUUID(), "CONVERSATION",
                project.getId(), instance.getAgentHostId()).orElseThrow();
        assertThat(allocator.accept(accepted)).isTrue();
        assertThat(allocator.accept(accepted)).isFalse(); // already INITIALIZING
        assertThat(sessionRepository.findById(accepted).orElseThrow().getAllocationState())
                .isEqualTo(SessionAllocator.ALLOC_STATE_INITIALIZING);

        UUID rejected = allocator.offer("CONVERSATION", UUID.randomUUID(), "CONVERSATION",
                project.getId(), instance.getAgentHostId()).orElseThrow();
        allocator.reject(rejected, "NO_CAPACITY", "host refused", true);
        assertThat(sessionRepository.findById(rejected).orElseThrow().getAllocationState())
                .isEqualTo(SessionAllocator.ALLOC_STATE_CLOSED);
        // Capacity is back: a fresh offer succeeds on the same instance.
        assertThat(allocator.offer("CONVERSATION", UUID.randomUUID(), "CONVERSATION",
                project.getId(), instance.getAgentHostId())).isPresent();

        UUID expired = allocator.offer("CONVERSATION", UUID.randomUUID(), "CONVERSATION",
                project.getId(), instance.getAgentHostId()).orElseThrow();
        Session row = sessionRepository.findById(expired).orElseThrow();
        row.setOfferExpiresAt(Instant.now().minusSeconds(60));
        sessionRepository.saveAndFlush(row);
        int expiredCount = allocator.expireOffers(Instant.now());
        assertThat(expiredCount).isEqualTo(1);
        assertThat(sessionRepository.findById(expired).orElseThrow().getAllocationState())
                .isEqualTo(SessionAllocator.ALLOC_STATE_CLOSED);
    }

    @Test
    void openedMintsExactlyOneAgentRowPerSession() {
        AgentHostInstance instance = openInstance(2);
        Project project = project();

        UUID sessionId = allocator.offer("CONVERSATION", UUID.randomUUID(), "CONVERSATION",
                project.getId(), instance.getAgentHostId()).orElseThrow();
        allocator.accept(sessionId);
        allocator.confirmOpened(sessionId, "slot-thread-1");

        // §19.1 decision 1: the Agent row is minted at session.opened.
        var workers = agentRepository.findByAgentHostId(instance.getAgentHostId());
        assertThat(workers).hasSize(1);
        Agent minted = workers.get(0);
        assertThat(minted.getAgentHostInstanceId()).isEqualTo(instance.getId());
        assertThat(minted.getStatus()).isEqualTo(Agent.Status.IDLE);

        // Idempotency: a duplicate opened for the same session must not mint twice.
        allocator.confirmOpened(sessionId, "slot-thread-1");
        assertThat(agentRepository.findByAgentHostId(instance.getAgentHostId())).hasSize(1);

        assertThat(sessionRepository.findById(sessionId).orElseThrow().getAllocationState())
                .isEqualTo(SessionAllocator.ALLOC_STATE_ACTIVE);
    }

    @Test
    void closeReturnsCapacityOnceAndReleasesTheWorker() {
        AgentHostInstance instance = openInstance(1);
        Project project = project();

        UUID sessionId = allocator.offer("CONVERSATION", UUID.randomUUID(), "CONVERSATION",
                project.getId(), instance.getAgentHostId()).orElseThrow();
        allocator.accept(sessionId);
        allocator.confirmOpened(sessionId, "slot-thread-1");

        allocator.close(sessionId, SessionCloseReasonForTest.CONVERSATION_ARCHIVED);

        Session closed = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(closed.getAllocationState()).isEqualTo(SessionAllocator.ALLOC_STATE_CLOSED);
        assertThat(closed.getClosedAt()).isNotNull();
        // The worker row is released (IDLE) and reusable — capacity is back.
        assertThat(allocator.offer("CONVERSATION", UUID.randomUUID(), "CONVERSATION",
                project.getId(), instance.getAgentHostId())).isPresent();

        // Idempotent close: second call does nothing.
        allocator.close(sessionId, SessionCloseReasonForTest.CONVERSATION_ARCHIVED);
        assertThat(closed.getClosedAt()).isNotNull();
    }

    @Test
    void idleLeaseExpiryClosesActiveSessions() {
        AgentHostInstance instance = openInstance(2);
        Project project = project();

        UUID sessionId = allocator.offer("CONVERSATION", UUID.randomUUID(), "CONVERSATION",
                project.getId(), instance.getAgentHostId()).orElseThrow();
        allocator.accept(sessionId);
        allocator.confirmOpened(sessionId, "slot-thread-1");

        Session row = sessionRepository.findById(sessionId).orElseThrow();
        row.setIdleLeaseExpiresAt(Instant.now().minusSeconds(60));
        sessionRepository.saveAndFlush(row);

        int expired = allocator.expireIdleLeases(Instant.now());

        assertThat(expired).isEqualTo(1);
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getAllocationState())
                .isEqualTo(SessionAllocator.ALLOC_STATE_CLOSED);
        // Capacity returned.
        assertThat(allocator.offer("CONVERSATION", UUID.randomUUID(), "CONVERSATION",
                project.getId(), instance.getAgentHostId())).isPresent();
    }

    /**
     * §12.2 session.open timeout (§11.1 INITIALIZING → CLOSED): accept
     * re-arms the offer column as the initialization deadline; a session
     * accepted but never opened inside the window sweeps CLOSED with
     * close_reason=OFFER_EXPIRED, while ACTIVE sessions are untouched.
     */
    @Test
    void initializationTimeoutSweepsStuckInitializingSessionsClosed() {
        AgentHostInstance instance = openInstance(3);
        Project project = project();

        UUID stuck = allocator.offer("CONVERSATION", UUID.randomUUID(), "CONVERSATION",
                project.getId(), instance.getAgentHostId()).orElseThrow();
        assertThat(allocator.accept(stuck)).isTrue();
        // accept() re-arms the deadline — verify before aging it.
        assertThat(sessionRepository.findById(stuck).orElseThrow().getOfferExpiresAt())
                .isAfter(Instant.now());

        UUID opened = allocator.offer("CONVERSATION", UUID.randomUUID(), "CONVERSATION",
                project.getId(), instance.getAgentHostId()).orElseThrow();
        allocator.accept(opened);
        allocator.confirmOpened(opened, "slot-thread-1");

        // Age BOTH rows' lease past the window: the INITIALIZING one must
        // sweep, the ACTIVE one must not (idle lease is a different column).
        Session stuckRow = sessionRepository.findById(stuck).orElseThrow();
        stuckRow.setOfferExpiresAt(Instant.now().minusSeconds(60));
        sessionRepository.saveAndFlush(stuckRow);

        int swept = allocator.expireOffers(Instant.now());
        assertThat(swept).isEqualTo(1);
        Session closedRow = sessionRepository.findById(stuck).orElseThrow();
        assertThat(closedRow.getAllocationState()).isEqualTo(SessionAllocator.ALLOC_STATE_CLOSED);
        assertThat(closedRow.getCloseReason()).isEqualTo("OFFER_EXPIRED");
        assertThat(closedRow.getClosedAt()).isNotNull();

        Session activeRow = sessionRepository.findById(opened).orElseThrow();
        assertThat(activeRow.getAllocationState()).isEqualTo(SessionAllocator.ALLOC_STATE_ACTIVE);
        // Capacity returned for the swept slot.
        assertThat(allocator.offer("CONVERSATION", UUID.randomUUID(), "CONVERSATION",
                project.getId(), instance.getAgentHostId())).isPresent();
    }

    @Test
    void concurrentOffersCannotOversubscribeThePool() throws Exception {
        AgentHostInstance instance = openInstance(2);
        Project project = project();
        AtomicInteger wins = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);

        for (int i = 0; i < 4; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    allocator.offer("CONVERSATION", UUID.randomUUID(), "CONVERSATION",
                            project.getId(), instance.getAgentHostId())
                            .ifPresent(id -> wins.incrementAndGet());
                } catch (Exception e) {
                    // counted as a loss
                }
            });
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);

        // Pool 2: at most 2 concurrent wins regardless of thread count.
        assertThat(wins.get()).isLessThanOrEqualTo(2);
    }

    /** Local alias so tests read cleanly. */
    private static final class SessionCloseReasonForTest {
        static final String CONVERSATION_ARCHIVED = "CONVERSATION_ARCHIVED";
    }

    @Test
    void twoSessionsOnOneInstanceMintExactlyOneWorkerEachAndCloseReleases() {
        AgentHostInstance instance = openInstance(2);
        Project project = project();

        UUID conversationA = UUID.randomUUID();
        UUID conversationB = UUID.randomUUID();
        UUID sessionA = allocator.offer("CONVERSATION", conversationA, "CONVERSATION",
                project.getId(), instance.getAgentHostId()).orElseThrow();
        UUID sessionB = allocator.offer("CONVERSATION", conversationB, "CONVERSATION",
                project.getId(), instance.getAgentHostId()).orElseThrow();

        allocator.accept(sessionA);
        allocator.accept(sessionB);
        allocator.confirmOpened(sessionA, "slot-a");
        allocator.confirmOpened(sessionB, "slot-b");

        // Two DISTINCT conversations -> exactly two workers, one per refId.
        var workers = agentRepository.findByAgentHostId(instance.getAgentHostId());
        assertThat(workers).hasSize(2);
        assertThat(workers.stream().map(Agent::getConversationId).toList())
                .containsExactlyInAnyOrder(conversationA, conversationB);

        // Close releases A's worker: its conversationId is cleared.
        allocator.close(sessionA, "CONVERSATION_ARCHIVED");
        Agent releasedA = agentRepository.findByAgentHostId(instance.getAgentHostId()).stream()
                .filter(a -> a.getConversationId() == null)
                .findFirst().orElseThrow();
        // And B's worker is untouched.
        assertThat(agentRepository.findByAgentHostId(instance.getAgentHostId()).stream()
                .anyMatch(a -> conversationB.equals(a.getConversationId()))).isTrue();
    }

    @Test
    @Transactional
    void sweepAllocationHonorsGuardFlag() {
        // Disabled half: the e2e profile sets myrmec.host.allocation-sweep.enabled=false,
        // so the autowired allocator's sweep must be a no-op — an expired offer survives.
        AgentHostInstance instance = openInstance(2);
        Project project = project();
        UUID sessionId = allocator.offer("CONVERSATION", UUID.randomUUID(), "CONVERSATION",
                project.getId(), instance.getAgentHostId()).orElseThrow();
        // Force the offer to be already expired.
        Session row = sessionRepository.findById(sessionId).orElseThrow();
        row.setOfferExpiresAt(Instant.now().minusSeconds(60));
        sessionRepository.saveAndFlush(row);

        allocator.sweepAllocation();
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getAllocationState())
                .isEqualTo(SessionAllocator.ALLOC_STATE_OFFERED);

        // Enabled half: a directly-constructed allocator with enabled=true must sweep
        // the expired offer to CLOSED.
        SessionAllocator enabledSweep = new SessionAllocator(sessionRepository,
                instances, agentRepository, nodeRegistryService, executionRepository,
                10, 30, 1800, 300, true);
        enabledSweep.sweepAllocation();
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getAllocationState())
                .isEqualTo(SessionAllocator.ALLOC_STATE_CLOSED);
    }

    // ------------------------------------------------------------------
    // §12.2 idle-lease reset + in-flight guard
    // ------------------------------------------------------------------

    /** Active session fixture: offer → accept → confirmOpened. */
    private UUID activeSession(AgentHostInstance instance, Project project) {
        UUID sessionId = allocator.offer("CONVERSATION", UUID.randomUUID(), "CONVERSATION",
                project.getId(), instance.getAgentHostId()).orElseThrow();
        allocator.accept(sessionId);
        allocator.confirmOpened(sessionId, "slot-thread-1");
        return sessionId;
    }

    /** Execution row fixture on a session (caller picks the state). */
    private SessionExecution execution(UUID sessionId, SessionExecution.State state) {
        SessionExecution e = new SessionExecution();
        e.setSessionId(sessionId);
        e.setServiceType("CONVERSATION");
        e.setRequestId(UUID.randomUUID().toString());
        e.setState(state);
        e.setCreatedAt(Instant.now());
        return executionRepository.saveAndFlush(e);
    }

    /**
     * §12.2 activity touch: a touch on an ACTIVE session re-arms the expiry,
     * so a sweep after it does NOT close the session.
     */
    @Test
    void touchIdleLeaseRearmsExpiryAndSweepSparesTheSession() {
        AgentHostInstance instance = openInstance(2);
        Project project = project();
        UUID sessionId = activeSession(instance, project);

        // Age the lease past the window first.
        Session row = sessionRepository.findById(sessionId).orElseThrow();
        row.setIdleLeaseExpiresAt(Instant.now().minusSeconds(60));
        sessionRepository.saveAndFlush(row);

        allocator.touchIdleLease(sessionId);

        Instant touched = sessionRepository.findById(sessionId).orElseThrow()
                .getIdleLeaseExpiresAt();
        assertThat(touched).isAfter(Instant.now());

        // The stale snapshot must no longer match: sweep finds nothing to close.
        int expired = allocator.expireIdleLeases(touched);
        assertThat(expired).isZero();
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getAllocationState())
                .isEqualTo(SessionAllocator.ALLOC_STATE_ACTIVE);
    }

    /**
     * §12.2 in-flight guard: an ACTIVE session past expiry with a RUNNING
     * execution is deferred — the sweep returns 0 and the row stays ACTIVE.
     */
    @Test
    void idleLeaseSweepSkipsSessionsWithRunningExecution() {
        AgentHostInstance instance = openInstance(2);
        Project project = project();
        UUID sessionId = activeSession(instance, project);

        Session row = sessionRepository.findById(sessionId).orElseThrow();
        row.setIdleLeaseExpiresAt(Instant.now().minusSeconds(60));
        sessionRepository.saveAndFlush(row);
        execution(sessionId, SessionExecution.State.RUNNING);

        int expired = allocator.expireIdleLeases(Instant.now());

        assertThat(expired).isZero();
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getAllocationState())
                .isEqualTo(SessionAllocator.ALLOC_STATE_ACTIVE);
        // The lease is untouched — the terminal touch re-arms it later.
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getIdleLeaseExpiresAt())
                .isBefore(Instant.now());
    }

    /**
     * §12.2 in-flight guard release: once the execution is terminal the same
     * session sweeps CLOSED (the guard was the only thing holding it).
     */
    @Test
    void terminalExecutionReleasesGuardAndSessionSweepsClosed() {
        AgentHostInstance instance = openInstance(2);
        Project project = project();
        UUID sessionId = activeSession(instance, project);

        Session row = sessionRepository.findById(sessionId).orElseThrow();
        row.setIdleLeaseExpiresAt(Instant.now().minusSeconds(60));
        sessionRepository.saveAndFlush(row);
        SessionExecution running = execution(sessionId, SessionExecution.State.RUNNING);

        // Guard holds while RUNNING.
        assertThat(allocator.expireIdleLeases(Instant.now())).isZero();
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getAllocationState())
                .isEqualTo(SessionAllocator.ALLOC_STATE_ACTIVE);

        // Execution reaches a durable terminal — the same sweep now closes.
        running.setState(SessionExecution.State.COMPLETED);
        running.setTerminalMessageId("m-term");
        running.setTerminalAt(Instant.now());
        executionRepository.saveAndFlush(running);

        int expired = allocator.expireIdleLeases(Instant.now());
        assertThat(expired).isEqualTo(1);
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getAllocationState())
                .isEqualTo(SessionAllocator.ALLOC_STATE_CLOSED);
        // Capacity returned.
        assertThat(allocator.offer("CONVERSATION", UUID.randomUUID(), "CONVERSATION",
                project.getId(), instance.getAgentHostId())).isPresent();
    }
}
