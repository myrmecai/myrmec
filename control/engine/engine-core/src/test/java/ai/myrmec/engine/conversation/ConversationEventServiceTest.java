package ai.myrmec.engine.conversation;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 4e — the append-only conversation lifecycle log persists against a
 * real H2 DB, assigns a monotonic {@code seq}, and derives
 * {@code bindAttemptNo} from the conversation's reserve history
 * (conversation-observability §4).
 */
class ConversationEventServiceTest extends IntegrationTestBase {

    @Autowired private TestDataBuilder data;
    @Autowired private ConversationEventService events;
    @Autowired private ConversationEventRepository eventRepository;
    @Autowired private ConversationRepository conversationRepository;

    @Test
    void recordsMonotonicSeqStartingAtZero() {
        UUID conversationId = conversation();
        UUID agentId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();

        events.record(conversationId, agentId, hostId,
                Agent.Status.IDLE, Agent.Status.RESERVED, ConversationEventReason.RESERVED);
        events.record(conversationId, agentId, hostId,
                Agent.Status.RESERVED, Agent.Status.CONNECTING, ConversationEventReason.BIND_ACKED);
        events.record(conversationId, agentId, hostId,
                Agent.Status.CONNECTING, Agent.Status.BOUND, ConversationEventReason.INSTANCE_BOUND);

        List<ConversationEvent> log = eventRepository.findByConversationIdOrderBySeqAsc(conversationId);
        assertThat(log).extracting(ConversationEvent::getSeq).containsExactly(0, 1, 2);
        assertThat(log).extracting(ConversationEvent::getReasonCode).containsExactly(
                ConversationEventReason.RESERVED,
                ConversationEventReason.BIND_ACKED,
                ConversationEventReason.INSTANCE_BOUND);
        assertThat(log.get(0).getFromState()).isEqualTo("IDLE");
        assertThat(log.get(2).getToState()).isEqualTo("BOUND");
        assertThat(log).allSatisfy(e -> {
            assertThat(e.getAgentId()).isEqualTo(agentId);
            assertThat(e.getAgentHostId()).isEqualTo(hostId);
            assertThat(e.getOccurredAt()).isNotNull();
        });
    }

    @Test
    void derivesBindAttemptNumberFromReserveHistory() {
        UUID conversationId = conversation();

        // First attempt: reserve, ack, release.
        events.record(conversationId, null, null,
                Agent.Status.IDLE, Agent.Status.RESERVED, ConversationEventReason.RESERVED);
        events.record(conversationId, null, null,
                Agent.Status.RESERVED, Agent.Status.CONNECTING, ConversationEventReason.BIND_ACKED);
        events.record(conversationId, null, null,
                Agent.Status.CONNECTING, Agent.Status.IDLE, ConversationEventReason.RELEASED);
        // Second attempt: reserve again, then bound.
        events.record(conversationId, null, null,
                Agent.Status.IDLE, Agent.Status.RESERVED, ConversationEventReason.RESERVED);
        events.record(conversationId, null, null,
                Agent.Status.RESERVED, Agent.Status.BOUND, ConversationEventReason.INSTANCE_BOUND);

        List<ConversationEvent> log = eventRepository.findByConversationIdOrderBySeqAsc(conversationId);
        assertThat(log).extracting(ConversationEvent::getBindAttemptNo)
                .containsExactly(1, 1, 1, 2, 2);
    }

    @Test
    void nullConversationIsANoOp() {
        UUID conversationId = conversation();

        ConversationEvent recorded = events.record(null, UUID.randomUUID(), UUID.randomUUID(),
                Agent.Status.IDLE, Agent.Status.RESERVED, ConversationEventReason.RESERVED);

        assertThat(recorded).isNull();
        assertThat(eventRepository.findByConversationIdOrderBySeqAsc(conversationId)).isEmpty();
    }

    private UUID conversation() {
        Project project = data.project().named("events-" + UUID.randomUUID()).create();
        Conversation conversation = new Conversation();
        conversation.setProjectId(project.getId());
        conversation.setTitle("events-test");
        return conversationRepository.save(conversation).getId();
    }
}
