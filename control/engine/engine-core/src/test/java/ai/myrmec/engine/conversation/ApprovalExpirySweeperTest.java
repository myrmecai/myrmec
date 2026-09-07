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
    // HITL slice B: the ORCH_REVIEW task sweep.
    @Autowired private ai.myrmec.engine.workflow.WorkflowTaskRepository workflowTaskRepository;
    @Autowired private ai.myrmec.engine.workflow.WorkflowRequestRepository requestRepository;
    @Autowired private ai.myrmec.engine.workflow.WorkflowRepository workflowRepository;
    @Autowired private ai.myrmec.engine.workflow.TaskAttemptRepository attemptRepository;

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

    // ── HITL slice B (§17.4/§16.6): the ORCH_REVIEW task sweep ──

    @Test
    void sweepExpiresOrchestrationReviewTasksPastTheirDeadline() {
        // Arrange: an ORCH_REVIEW task whose approval deadline passed.
        var seeded = seedOrchestrationReviewTask(Instant.now().minusSeconds(60));

        sweeper.sweep();

        var stored = workflowTaskRepository.findById(seeded.taskId()).orElseThrow();
        assertThat(stored.getApprovalStatus())
                .as("§16.6: the sweeper applies the terminal APPROVAL_EXPIRED tuple")
                .isEqualTo("EXPIRED");
        assertThat(stored.getStatus())
                .isEqualTo(ai.myrmec.engine.workflow.TaskStatus.COMPLETED);
        assertThat(stored.getErrorMessage()).isEqualTo("APPROVAL_EXPIRED");
        var storedRequest = requestRepository.findById(stored.getRequest().getId()).orElseThrow();
        assertThat(storedRequest.getStatus())
                .isEqualTo(ai.myrmec.engine.workflow.RequestStatus.FAILED);

        // Idempotent: a second sweep does not re-apply (already decided).
        sweeper.sweep();
        var again = workflowTaskRepository.findById(seeded.taskId()).orElseThrow();
        assertThat(again.getApprovalStatus()).isEqualTo("EXPIRED");
    }

    @Test
    void sweepIgnoresOrchestrationReviewsStillWithinDeadline() {
        var seeded = seedOrchestrationReviewTask(Instant.now().plusSeconds(3600));

        sweeper.sweep();

        var stored = workflowTaskRepository.findById(seeded.taskId()).orElseThrow();
        assertThat(stored.getApprovalStatus())
                .as("future-deadline ORCH_REVIEW tasks must remain PENDING")
                .isEqualTo("PENDING");
        assertThat(stored.getStatus())
                .isEqualTo(ai.myrmec.engine.workflow.TaskStatus.PAUSED);
    }

    /** Seed one ORCH_REVIEW workflow task at the given expiry. */
    private SeededTask seedOrchestrationReviewTask(Instant approvalExpiresAt) {
        ai.myrmec.engine.project.Project project =
                data.project().named("orch-sweep-" + System.nanoTime()).withRepo("https://x.git", "main").create();
        ai.myrmec.engine.agent.AgentProfile profile =
                data.agentProfile().named("orch-sweep-profile").create();
        var user = userRepository.findById(TEST_ADMIN_ID).orElseThrow();

        var wf = new ai.myrmec.engine.workflow.Workflow();
        wf.setProject(project);
        wf.setName("orch-sweep-wf-" + System.nanoTime());
        wf.setSteps(java.util.List.<java.util.Map<String, Object>>of());
        wf.setVersion(1);
        wf.setStatus(ai.myrmec.engine.workflow.WorkflowStatus.PUBLISHED);
        wf.setCreatedBy(user);
        wf = workflowRepository.save(wf);

        var req = new ai.myrmec.engine.workflow.WorkflowRequest();
        req.setWorkflow(wf);
        req.setWorkflowVersion(1);
        req.setInput(java.util.Map.of());
        req.setStatus(ai.myrmec.engine.workflow.RequestStatus.PAUSED);
        req.setBranch("myrmec/orch-sweep");
        req.setCreatedBy(user);
        req.setCreatedAt(Instant.now());
        req = requestRepository.save(req);

        var task = new ai.myrmec.engine.workflow.WorkflowTask();
        task.setRequest(req);
        task.setStepId("build");
        task.setAgentProfile(profile);
        task.setInput(java.util.Map.of());
        task.setStatus(ai.myrmec.engine.workflow.TaskStatus.PAUSED);
        task.setAttempt(1);
        task.setMaxRetries(1);
        task.setPauseState("ORCH_REVIEW");
        task.setPausedAt(Instant.now());
        task.setApprovalStatus("PENDING");
        task.setApprovalRequestedAt(Instant.now());
        task.setApprovalExpiresAt(approvalExpiresAt);
        task.setApprovalPayload(java.util.Map.of(
                "approvalRequestId", UUID.randomUUID().toString(),
                "stateDigest", "b".repeat(64),
                "action", java.util.Map.of(
                        "actionId", "action-1", "type", "WORKER_TOOL",
                        "riskClass", "DESTRUCTIVE", "summary", "worker:impl:edit",
                        "digest", "a".repeat(64))));
        var output = new java.util.HashMap<String, Object>();
        output.put("summary", "suspended");
        output.put("suspension", java.util.Map.of(
                "continuationId", "cont-sweep",
                "continuationRef", "local:cont-sweep",
                "snapshotTreeHash", "c".repeat(40),
                "workspaceRevision", 2,
                "stateDigest", "b".repeat(64),
                "reason", "HITL_APPROVAL"));
        task.setOutput(output);
        task = workflowTaskRepository.save(task);

        var attempt = task.createAttempt(null);
        attempt.setStatus(ai.myrmec.engine.workflow.AttemptStatus.PAUSED);
        attemptRepository.save(attempt);

        return new SeededTask(task.getId());
    }

    record SeededTask(UUID taskId) {}

    private ConversationSubscriber stubSubscriber(BlockingQueue<String> outbound) {
        return new ConversationSubscriber() {
            private final String id = "expiry-stub-" + UUID.randomUUID();
            @Override public String id() { return id; }
            @Override public boolean isOpen() { return true; }
            @Override public void send(String jsonFrame) { outbound.add(jsonFrame); }
        };
    }
}
