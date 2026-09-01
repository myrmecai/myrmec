package ai.myrmec.engine.agent;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 3b — covers the reserve-time binding primitives against a real H2
 * DB: the atomic IDLE → RESERVED compare-and-set, idempotent release, and
 * the heartbeat guard that must not yank a bound worker back into the pool
 * (agent-concurrency §9.5).
 */
class AgentReservationTest extends IntegrationTestBase {

    @Autowired private TestDataBuilder data;
    @Autowired private AgentHostService agentService;
    @Autowired private AgentRepository instanceRepository;

    @Test
    void reserveAtomicallyClaimsAnIdleWorkerAndPinsBindings() {
        Agent worker = idleWorker();
        UUID conversationId = UUID.randomUUID();
        UUID profileVersionId = UUID.randomUUID();

        boolean claimed = agentService.reserveInstance(worker.getId(), conversationId, profileVersionId);

        assertThat(claimed).isTrue();
        Agent reloaded = instanceRepository.findById(worker.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Agent.Status.RESERVED);
        assertThat(reloaded.getConversationId()).isEqualTo(conversationId);
        assertThat(reloaded.getProfileVersionId()).isEqualTo(profileVersionId);
        assertThat(reloaded.getStateChangedAt()).isNotNull();
    }

    @Test
    void bindAckAdvancesAReservedWorkerToConnecting() {
        Agent worker = idleWorker();
        UUID conversationId = UUID.randomUUID();
        agentService.reserveInstance(worker.getId(), conversationId, UUID.randomUUID());

        boolean acked = agentService.confirmBind(worker.getId(), conversationId);

        assertThat(acked).isTrue();
        Agent reloaded = instanceRepository.findById(worker.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Agent.Status.CONNECTING);
    }

    @Test
    void bindAckForWrongConversationIsRejected() {
        Agent worker = idleWorker();
        agentService.reserveInstance(worker.getId(), UUID.randomUUID(), UUID.randomUUID());

        boolean acked = agentService.confirmBind(worker.getId(), UUID.randomUUID());

        assertThat(acked).isFalse();
        Agent reloaded = instanceRepository.findById(worker.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Agent.Status.RESERVED);
    }

    @Test
    void bindNackReleasesAReservedWorkerBackToThePool() {
        Agent worker = idleWorker();
        UUID conversationId = UUID.randomUUID();
        agentService.reserveInstance(worker.getId(), conversationId, UUID.randomUUID());

        boolean released = agentService.rejectBind(worker.getId(), conversationId, "no capacity");

        assertThat(released).isTrue();
        Agent reloaded = instanceRepository.findById(worker.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Agent.Status.IDLE);
        assertThat(reloaded.getConversationId()).isNull();
        assertThat(reloaded.getProfileVersionId()).isNull();
    }

    @Test
    void bindNackReleasesAConnectingWorker() {
        Agent worker = idleWorker();
        UUID conversationId = UUID.randomUUID();
        agentService.reserveInstance(worker.getId(), conversationId, UUID.randomUUID());
        agentService.confirmBind(worker.getId(), conversationId);

        boolean released = agentService.rejectBind(worker.getId(), conversationId, "dial failed");

        assertThat(released).isTrue();
        Agent reloaded = instanceRepository.findById(worker.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Agent.Status.IDLE);
    }

    @Test
    void attachFlipsAConnectingWorkerToBound() {
        Agent worker = idleWorker();
        UUID conversationId = UUID.randomUUID();
        agentService.reserveInstance(worker.getId(), conversationId, UUID.randomUUID());
        agentService.confirmBind(worker.getId(), conversationId);

        boolean bound = agentService.attachConversation(worker.getId(), conversationId, null);

        assertThat(bound).isTrue();
        Agent reloaded = instanceRepository.findById(worker.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Agent.Status.BOUND);
    }

    @Test
    void reserveLosesTheRaceWhenWorkerIsNoLongerIdle() {
        Agent worker = idleWorker();
        UUID firstConv = UUID.randomUUID();

        assertThat(agentService.reserveInstance(worker.getId(), firstConv, UUID.randomUUID()))
                .isTrue();

        // Second claim must fail (compare-and-set guard) and leave the
        // original binding untouched.
        boolean second = agentService.reserveInstance(
                worker.getId(), UUID.randomUUID(), UUID.randomUUID());

        assertThat(second).isFalse();
        Agent reloaded = instanceRepository.findById(worker.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Agent.Status.RESERVED);
        assertThat(reloaded.getConversationId()).isEqualTo(firstConv);
    }

    @Test
    void releaseReturnsWorkerToThePoolAndClearsBindings() {
        Agent worker = idleWorker();
        agentService.reserveInstance(worker.getId(), UUID.randomUUID(), UUID.randomUUID());

        agentService.releaseInstance(worker.getId());

        Agent reloaded = instanceRepository.findById(worker.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Agent.Status.IDLE);
        assertThat(reloaded.getConversationId()).isNull();
        assertThat(reloaded.getProfileVersionId()).isNull();
    }

    @Test
    void heartbeatDoesNotClobberAReservedWorker() {
        Agent worker = idleWorker();
        agentService.reserveInstance(worker.getId(), UUID.randomUUID(), UUID.randomUUID());

        Agent reserved = instanceRepository.findById(worker.getId()).orElseThrow();
        reserved.recordHeartbeat();
        instanceRepository.save(reserved);

        Agent reloaded = instanceRepository.findById(worker.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Agent.Status.RESERVED);
        assertThat(reloaded.getLastHeartbeatAt()).isNotNull();
    }

    @Test
    void heartbeatRevivesADeadWorkerIntoThePool() {
        Agent worker = idleWorker();
        worker.setStatus(Agent.Status.DEAD);
        instanceRepository.save(worker);

        Agent dead = instanceRepository.findById(worker.getId()).orElseThrow();
        dead.recordHeartbeat();
        instanceRepository.save(dead);

        Agent reloaded = instanceRepository.findById(worker.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Agent.Status.IDLE);
    }

    private Agent idleWorker() {
        AgentProfile profile = data.agentProfile()
                .named("reservation-profile-" + UUID.randomUUID())
                .withSystemPrompt("test")
                .create();
        var project = data.project().named("reservation-" + UUID.randomUUID()).create();
        AgentHost host = data.agent()
                .named("reservation-host-" + UUID.randomUUID())
                .withProfile(profile)
                .inProject(project)
                .create()
                .agent();

        Agent worker = new Agent();
        worker.setAgentHostId(host.getId());
        worker.setHostname("res-host");
        worker.setRuntimeVersion("0.0.0");
        worker.setStatus(Agent.Status.IDLE);
        worker.setRegisteredAt(Instant.now().minus(1, ChronoUnit.HOURS));
        worker.setLastHeartbeatAt(Instant.now());
        return instanceRepository.save(worker);
    }
}
