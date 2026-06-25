package ai.myrmec.engine.conversation;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.conversation.stream.ConversationStreamBroker;
import ai.myrmec.engine.conversation.stream.ConversationSubscriber;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7e — verifies the {@link ApprovalExpirySweeper} flips PENDING
 * approval rows past their expiresAt to EXPIRED, broadcasts the
 * decision frame to subscribed viewers, and is idempotent on repeat
 * sweeps.
 */
class ApprovalExpirySweeperTest extends IntegrationTestBase {

    @Autowired private TestDataBuilder data;
    @Autowired private ConversationService conversationService;
    @Autowired private ConversationMessageRepository messageRepository;
    @Autowired private ApprovalExpirySweeper sweeper;
    @Autowired private ConversationStreamBroker broker;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void sweepFlipsExpiredRowsToExpiredAndBroadcastsDecisionFrame() throws Exception {
        Project project = data.project().named("expiry-sweep").create();
        AgentProfile profile = data.agentProfile()
                .named("expiry-profile").withSystemPrompt("p").create();
        AgentHost agent = data.agent()
                .named("expiry-agent").withProfile(profile).inProject(project)
                .create().agent();
        Conversation conv = conversationService.createConversation(
                project.getId(), TEST_ADMIN_ID, "expiry-conv", agent.getId(), null);

        UUID clientReqId = UUID.randomUUID();
        Instant past = Instant.now().minus(5, ChronoUnit.MINUTES);
        ConversationMessage request = conversationService.appendApprovalRequest(
                conv.getId(), agent.getId(),
                "destructive op",
                "{\"clientRequestId\":\"" + clientReqId + "\",\"sql\":\"DROP TABLE x\"}",
                past);

        // Subscribe a stub viewer so the expiry frame can be observed.
        BlockingQueue<String> viewerInbound = new LinkedBlockingQueue<>();
        ConversationSubscriber viewer = stubSubscriber(viewerInbound);
        broker.subscribe(conv.getId(), viewer);

        // ---------- Act ----------
        sweeper.sweep();

        // ---------- Assert: persisted row flipped ----------
        ConversationMessage reloaded = messageRepository.findById(request.getId()).orElseThrow();
        assertThat(reloaded.getApprovalStatus())
                .as("sweeper must move PENDING past expiry to EXPIRED")
                .isEqualTo(ConversationMessage.ApprovalStatus.EXPIRED);

        // ---------- Assert: viewer received an approval.decision frame ----------
        String frame = viewerInbound.poll(2, TimeUnit.SECONDS);
        assertThat(frame).as("viewer must see the expiry decision frame").isNotNull();
        JsonNode envelope = objectMapper.readTree(frame);
        assertThat(envelope.path("type").asText()).isEqualTo("approval.decision");
        JsonNode payload = envelope.path("payload");
        assertThat(payload.path("conversationId").asText()).isEqualTo(conv.getId().toString());
        assertThat(payload.path("requestMessageId").asText()).isEqualTo(request.getId().toString());
        assertThat(payload.path("decision").asText()).isEqualTo("EXPIRED");
        assertThat(payload.path("clientRequestId").asText())
                .as("clientRequestId must be recovered from payloadJson")
                .isEqualTo(clientReqId.toString());

        // ---------- Assert: second sweep is a no-op (idempotency) ----------
        sweeper.sweep();
        assertThat(viewerInbound.poll(500, TimeUnit.MILLISECONDS))
                .as("already-EXPIRED rows must not be re-broadcast")
                .isNull();

        broker.unsubscribe(conv.getId(), viewer);
    }

    @Test
    void sweepIgnoresPendingRowsWithFutureExpiry() {
        Project project = data.project().named("expiry-future").create();
        AgentProfile profile = data.agentProfile()
                .named("expiry-future-profile").withSystemPrompt("p").create();
        AgentHost agent = data.agent()
                .named("expiry-future-agent").withProfile(profile).inProject(project)
                .create().agent();
        Conversation conv = conversationService.createConversation(
                project.getId(), TEST_ADMIN_ID, "future-conv", agent.getId(), null);
        ConversationMessage row = conversationService.appendApprovalRequest(
                conv.getId(), agent.getId(), "fine", "{}",
                Instant.now().plus(1, ChronoUnit.HOURS));

        sweeper.sweep();

        assertThat(messageRepository.findById(row.getId()).orElseThrow().getApprovalStatus())
                .as("future-expiry rows must remain PENDING")
                .isEqualTo(ConversationMessage.ApprovalStatus.PENDING);
    }

    private ConversationSubscriber stubSubscriber(BlockingQueue<String> outbound) {
        return new ConversationSubscriber() {
            private final String id = "expiry-stub-" + UUID.randomUUID();
            @Override public String id() { return id; }
            @Override public boolean isOpen() { return true; }
            @Override public void send(String jsonFrame) { outbound.add(jsonFrame); }
        };
    }
}
