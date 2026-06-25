package ai.myrmec.engine.agent;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.conversation.ConversationEventReason;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 4d — covers the warm-worker FSM reaper against a real H2 DB: a
 * worker stuck in {@code RESERVED}/{@code CONNECTING} past the timeout is
 * reclaimed to {@code IDLE}, and a mid-lifecycle worker whose heartbeat has
 * gone stale is flipped to {@code DEAD} (agent-concurrency §9.5). The reaper
 * timer itself is disabled under the e2e profile; these tests drive the
 * package-private sweep methods directly.
 */
class AgentReaperServiceTest extends IntegrationTestBase {

    @Autowired private TestDataBuilder data;
    @Autowired private AgentReaperService reaper;
    @Autowired private AgentRepository instanceRepository;

    @Test
    void reserveTimeoutReclaimsAStuckReservedWorker() {
        Agent worker = worker(Agent.Status.RESERVED,
                Instant.now().minus(1, ChronoUnit.HOURS),   // stateChangedAt: stale
                Instant.now());                              // heartbeat: fresh
        worker.setConversationId(UUID.randomUUID());
        worker.setProfileVersionId(UUID.randomUUID());
        instanceRepository.save(worker);

        reaper.reapTransient(Instant.now(), Agent.Status.RESERVED, 10_000, ConversationEventReason.RESERVE_TIMEOUT);

        Agent reloaded = instanceRepository.findById(worker.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Agent.Status.IDLE);
        assertThat(reloaded.getConversationId()).isNull();
        assertThat(reloaded.getProfileVersionId()).isNull();
    }

    @Test
    void connectTimeoutReclaimsAStuckConnectingWorker() {
        Agent worker = worker(Agent.Status.CONNECTING,
                Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now());
        instanceRepository.save(worker);

        reaper.reapTransient(Instant.now(), Agent.Status.CONNECTING, 15_000, ConversationEventReason.CONNECT_TIMEOUT);

        Agent reloaded = instanceRepository.findById(worker.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Agent.Status.IDLE);
    }

    @Test
    void reserveTimeoutLeavesAFreshlyReservedWorkerAlone() {
        Agent worker = worker(Agent.Status.RESERVED, Instant.now(), Instant.now());
        instanceRepository.save(worker);

        reaper.reapTransient(Instant.now(), Agent.Status.RESERVED, 10_000, ConversationEventReason.RESERVE_TIMEOUT);

        Agent reloaded = instanceRepository.findById(worker.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Agent.Status.RESERVED);
    }

    @Test
    void hostLostFlipsAStaleBoundWorkerToDead() {
        Agent worker = worker(Agent.Status.BOUND,
                Instant.now(),                               // stateChangedAt: recent
                Instant.now().minus(5, ChronoUnit.MINUTES)); // heartbeat: stale
        instanceRepository.save(worker);

        reaper.reapHostLost(Instant.now());

        Agent reloaded = instanceRepository.findById(worker.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Agent.Status.DEAD);
    }

    @Test
    void hostLostLeavesAFreshlyHeartbeatingWorkerAlone() {
        Agent worker = worker(Agent.Status.BOUND, Instant.now(), Instant.now());
        instanceRepository.save(worker);

        reaper.reapHostLost(Instant.now());

        Agent reloaded = instanceRepository.findById(worker.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Agent.Status.BOUND);
    }

    @Test
    void hostLostIgnoresIdleWorkers() {
        Agent worker = worker(Agent.Status.IDLE, Instant.now(),
                Instant.now().minus(5, ChronoUnit.MINUTES));
        instanceRepository.save(worker);

        reaper.reapHostLost(Instant.now());

        Agent reloaded = instanceRepository.findById(worker.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Agent.Status.IDLE);
    }

    private Agent worker(Agent.Status status, Instant stateChangedAt, Instant lastHeartbeatAt) {
        AgentProfile profile = data.agentProfile()
                .named("reaper-profile-" + UUID.randomUUID())
                .withSystemPrompt("test")
                .create();
        var project = data.project().named("reaper-" + UUID.randomUUID()).create();
        AgentHost host = data.agent()
                .named("reaper-host-" + UUID.randomUUID())
                .withProfile(profile)
                .inProject(project)
                .create()
                .agent();

        Agent worker = new Agent();
        worker.setAgentHostId(host.getId());
        worker.setHostname("reaper-host");
        worker.setRuntimeVersion("0.0.0");
        worker.setStatus(status);
        worker.setRegisteredAt(Instant.now().minus(1, ChronoUnit.HOURS));
        worker.setLastHeartbeatAt(lastHeartbeatAt);
        worker.setStateChangedAt(stateChangedAt);
        return worker;
    }
}
