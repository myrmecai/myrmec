package ai.myrmec.engine.agent;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationEvent;
import ai.myrmec.engine.conversation.ConversationEventReason;
import ai.myrmec.engine.conversation.ConversationEventRepository;
import ai.myrmec.engine.conversation.ConversationRepository;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 4e — wires the append-only conversation log to the worker FSM: every
 * reserve/bind/attach/release and every reaper timeout stamps a
 * {@link ConversationEvent} attributed to the worker and its host
 * (agent-concurrency §9.5, conversation-observability §4). Transitions on a
 * worker not tied to a real conversation emit nothing.
 */
class AgentLifecycleEventTest extends IntegrationTestBase {

    @Autowired private TestDataBuilder data;
    @Autowired private AgentHostService agentService;
    @Autowired private AgentReaperService reaper;
    @Autowired private AgentRepository instanceRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private ConversationEventRepository eventRepository;

    @Test
    void reserveBindAttachReleaseLogsTheFullWorkerLifecycle() {
        UUID conversationId = conversation();
        Agent worker = idleWorker();
        UUID hostId = worker.getAgentHostId();

        agentService.reserveInstance(worker.getId(), conversationId, UUID.randomUUID());
        agentService.confirmBind(worker.getId(), conversationId);
        agentService.attachConversation(worker.getId(), conversationId, null);
        agentService.releaseInstance(worker.getId());

        List<ConversationEvent> log = eventRepository.findByConversationIdOrderBySeqAsc(conversationId);
        assertThat(log).extracting(ConversationEvent::getSeq).containsExactly(0, 1, 2, 3);
        assertThat(log).extracting(ConversationEvent::getReasonCode).containsExactly(
                ConversationEventReason.RESERVED,
                ConversationEventReason.BIND_ACKED,
                ConversationEventReason.INSTANCE_BOUND,
                ConversationEventReason.RELEASED);
        assertThat(log.get(0).getFromState()).isEqualTo("IDLE");
        assertThat(log.get(0).getToState()).isEqualTo("RESERVED");
        assertThat(log.get(2).getToState()).isEqualTo("BOUND");
        assertThat(log.get(3).getToState()).isEqualTo("IDLE");
        assertThat(log).allSatisfy(e -> {
            assertThat(e.getAgentId()).isEqualTo(worker.getId());
            assertThat(e.getAgentHostId()).isEqualTo(hostId);
        });
    }

    @Test
    void bindNackLogsTheReleaseReason() {
        UUID conversationId = conversation();
        Agent worker = idleWorker();

        agentService.reserveInstance(worker.getId(), conversationId, UUID.randomUUID());
        agentService.rejectBind(worker.getId(), conversationId, "no capacity");

        List<ConversationEvent> log = eventRepository.findByConversationIdOrderBySeqAsc(conversationId);
        assertThat(log).extracting(ConversationEvent::getReasonCode).containsExactly(
                ConversationEventReason.RESERVED,
                ConversationEventReason.BIND_NACKED);
        assertThat(log.get(1).getFromState()).isEqualTo("RESERVED");
        assertThat(log.get(1).getToState()).isEqualTo("IDLE");
    }

    @Test
    void reserveTimeoutLogsAReclaimEvent() {
        UUID conversationId = conversation();
        Agent worker = worker(Agent.Status.RESERVED,
                Instant.now().minus(1, ChronoUnit.HOURS),   // stateChangedAt: stale
                Instant.now(),                               // heartbeat: fresh
                conversationId);

        reaper.reapTransient(Instant.now(), Agent.Status.RESERVED, 10_000,
                ConversationEventReason.RESERVE_TIMEOUT);

        List<ConversationEvent> log = eventRepository.findByConversationIdOrderBySeqAsc(conversationId);
        assertThat(log).extracting(ConversationEvent::getReasonCode)
                .containsExactly(ConversationEventReason.RESERVE_TIMEOUT);
        assertThat(log.get(0).getFromState()).isEqualTo("RESERVED");
        assertThat(log.get(0).getToState()).isEqualTo("IDLE");
        assertThat(log.get(0).getAgentId()).isEqualTo(worker.getId());
        assertThat(log.get(0).getAgentHostId()).isEqualTo(worker.getAgentHostId());
    }

    @Test
    void hostLostLogsADeadEvent() {
        UUID conversationId = conversation();
        Agent worker = worker(Agent.Status.BOUND,
                Instant.now(),                               // stateChangedAt: recent
                Instant.now().minus(5, ChronoUnit.MINUTES),  // heartbeat: stale
                conversationId);

        reaper.reapHostLost(Instant.now());

        List<ConversationEvent> log = eventRepository.findByConversationIdOrderBySeqAsc(conversationId);
        assertThat(log).extracting(ConversationEvent::getReasonCode)
                .containsExactly(ConversationEventReason.HOST_LOST);
        assertThat(log.get(0).getFromState()).isEqualTo("BOUND");
        assertThat(log.get(0).getToState()).isEqualTo("DEAD");
    }

    private UUID conversation() {
        Project project = data.project().named("lifecycle-" + UUID.randomUUID()).create();
        Conversation conversation = new Conversation();
        conversation.setProjectId(project.getId());
        conversation.setTitle("lifecycle-test");
        return conversationRepository.save(conversation).getId();
    }

    private Agent idleWorker() {
        AgentProfile profile = data.agentProfile()
                .named("lifecycle-profile-" + UUID.randomUUID())
                .withSystemPrompt("test")
                .create();
        var project = data.project().named("lifecycle-host-" + UUID.randomUUID()).create();
        AgentHost host = data.agent()
                .named("lifecycle-host-" + UUID.randomUUID())
                .withProfile(profile)
                .inProject(project)
                .create()
                .agent();

        Agent worker = new Agent();
        worker.setAgentHostId(host.getId());
        worker.setHostname("lifecycle-host");
        worker.setRuntimeVersion("0.0.0");
        worker.setStatus(Agent.Status.IDLE);
        worker.setRegisteredAt(Instant.now().minus(1, ChronoUnit.HOURS));
        worker.setLastHeartbeatAt(Instant.now());
        return instanceRepository.save(worker);
    }

    private Agent worker(Agent.Status status, Instant stateChangedAt, Instant lastHeartbeatAt,
                         UUID conversationId) {
        Agent worker = idleWorker();
        worker.setStatus(status);
        worker.setConversationId(conversationId);
        worker.setStateChangedAt(stateChangedAt);
        worker.setLastHeartbeatAt(lastHeartbeatAt);
        return instanceRepository.save(worker);
    }
}
