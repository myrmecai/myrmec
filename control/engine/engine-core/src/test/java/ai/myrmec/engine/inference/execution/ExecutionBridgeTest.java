// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationMessage;
import ai.myrmec.engine.conversation.ConversationMessageRepository;
import ai.myrmec.engine.conversation.ConversationService;
import ai.myrmec.engine.conversation.stream.ConversationStreamBroker;
import ai.myrmec.engine.conversation.stream.ConversationSubscriber;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.user.User;
import ai.myrmec.engine.websocket.host.HostControlWebSocketHandler;
import ai.myrmec.engine.websocket.host.payload.ExecutionApprovalRequestedPayload;
import ai.myrmec.engine.websocket.host.payload.ExecutionCompletePayload;
import ai.myrmec.engine.websocket.host.payload.ExecutionEventPayload;
import ai.myrmec.engine.websocket.host.payload.ExecutionPausedPayload;
import ai.myrmec.engine.workflow.AttemptStatus;
import ai.myrmec.engine.workflow.RequestStatus;
import ai.myrmec.engine.workflow.TaskAttempt;
import ai.myrmec.engine.workflow.TaskResult;
import ai.myrmec.engine.workflow.TaskStatus;
import ai.myrmec.engine.workflow.Workflow;
import ai.myrmec.engine.workflow.WorkflowRequest;
import ai.myrmec.engine.workflow.WorkflowStatus;
import ai.myrmec.engine.workflow.WorkflowTask;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 4: ExecutionBridge downstream behaviour.
 *
 * <p>Tests the new bridge's conversation and orchestration arms in isolation,
 * using real repositories and the production broker. Orchestration fixtures
 * mirror {@link ai.myrmec.engine.workflow.OrchestrationOutcomeServiceTest}.
 *
 * <p>NOTE: deliberately NOT class-level @Transactional. The bridge's quota
 * recording runs REQUIRES_NEW inside its own transactional service; a
 * @Transactional test would suspend-and-hide the freshly created quota row
 * from the inner tx (read-committed isolation) and the recording would
 * silently no-op — probe-verified. Every write here goes through
 * transactional services, so no outer tx is needed.
 */
class ExecutionBridgeTest extends IntegrationTestBase {

    @Autowired
    private ExecutionBridge executionBridge;

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ConversationMessageRepository conversationMessageRepository;

    @Autowired
    private ConversationStreamBroker conversationStreamBroker;

    @Autowired
    private ai.myrmec.engine.inference.SessionRepository sessionRepository;

    @Autowired
    private ai.myrmec.engine.quota.QuotaService quotaService;

    @Autowired
    private ai.myrmec.engine.spi.quota.QuotaPolicyEngine quotaPolicyEngine;
    // Workflow repositories (inherited in cleanup order)
    @Autowired
    private ai.myrmec.engine.workflow.WorkflowRepository workflowRepository;

    @Autowired
    private ai.myrmec.engine.workflow.WorkflowRequestRepository workflowRequestRepository;

    @Autowired
    private ai.myrmec.engine.workflow.WorkflowTaskRepository workflowTaskRepository;

    @Autowired
    private ai.myrmec.engine.workflow.TaskAttemptRepository taskAttemptRepository;

    @Autowired
    private ai.myrmec.engine.workflow.ExecutionEventRepository executionEventRepository;

    @Autowired
    private ai.myrmec.engine.workflow.OrchestrationRunRepository orchestrationRunRepository;

    @Autowired
    private ai.myrmec.engine.workflow.OrchestrationDispatchRepository orchestrationDispatchRepository;

    @Autowired
    private ai.myrmec.engine.workflow.WorkspaceReleaseOrchestrator workspaceReleaseOrchestrator;

    // =================================================================
    // Conversation bridges
    // =================================================================

    @Test
    void conversationCompletePersistsAssistantRowBroadcastsAndRecordsQuota() {
        Project project = data.project().named("bridge-quota").create();
        AgentHostCreationResult hostResult = data.agent().named("bridge-host").withMaxAgents(10).create();
        AgentHost host = hostResult.agent();
        Conversation conversation = data.conversation().inProject(project).create();
        conversation.setAgentId(host.getId());
        conversationRepository.save(conversation);

        conversationService.appendMessage(conversation.getId(), ConversationMessage.Role.USER, "hi", TEST_ADMIN_ID, null);

        // Project quota so we can observe consumption.
        quotaService.create(
                ai.myrmec.engine.quota.Quota.Scope.PROJECT, project.getId(),
                ai.myrmec.engine.quota.Quota.ResourceType.TOKENS, ai.myrmec.engine.quota.Quota.Period.DAILY,
                10_000L, ai.myrmec.engine.quota.EnforcementMode.BLOCK, ai.myrmec.engine.quota.QuotaType.CEILING,
                null, null, null, TEST_ADMIN_ID);

        List<String> captured = new ArrayList<>();
        conversationStreamBroker.subscribe(conversation.getId(), new CapturingSubscriber(captured));

        ExecutionCompletePayload.Usage usage = new ExecutionCompletePayload.Usage("m1", 3L, 5L, 100L);
        ExecutionCompletePayload.Result result = new ExecutionCompletePayload.Result("assistant reply", Map.of(), List.of());
        executionBridge.onConversationComplete(
                conversation.getId(), project.getId(), host.getId(),
                new ExecutionCompletePayload(UUID.randomUUID(), Instant.now(), result, usage));

        List<ConversationMessage> messages = conversationMessageRepository
                .findByConversationIdOrderBySequenceNoAsc(conversation.getId());
        assertThat(messages).hasSize(2);
        ConversationMessage assistant = messages.get(1);
        assertThat(assistant.getRole()).isEqualTo(ConversationMessage.Role.ASSISTANT);
        assertThat(assistant.getContent()).isEqualTo("assistant reply");
        assertThat(assistant.getSequenceNo()).isEqualTo(1L);
        assertThat(assistant.getModelCode()).isEqualTo("m1");
        assertThat(assistant.getTokenCount()).isEqualTo(8);
        assertThat(assistant.getAuthorAgentId()).isEqualTo(host.getId());

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0)).contains("message.complete");
        assertThat(captured.get(0)).contains(assistant.getId().toString());

        // Consumption recorded at PROJECT scope (conversation has no assistant pinned) —
        // assert through the policy engine's own view, like BasicQuotaPolicyEngineTest.
        var decision = quotaPolicyEngine.check(
                ai.myrmec.engine.spi.quota.QuotaScope.PROJECT, project.getId(),
                ai.myrmec.engine.spi.quota.QuotaResourceType.TOKENS, 0);
        assertThat(decision.getConsumedAmount()).isEqualTo(8L);
    }

    @Test
    void conversationPausedPersistsApprovalAndPendingAction() {
        Project project = data.project().named("bridge-pause").create();
        AgentHostCreationResult hostResult = data.agent().named("bridge-pause-host").withMaxAgents(10).create();
        AgentHost host = hostResult.agent();
        Conversation conversation = data.conversation().inProject(project).create();
        conversation.setAgentId(host.getId());
        conversationRepository.save(conversation);

        conversationService.appendMessage(conversation.getId(), ConversationMessage.Role.USER, "go", TEST_ADMIN_ID, null);

        List<String> captured = new ArrayList<>();
        conversationStreamBroker.subscribe(conversation.getId(), new CapturingSubscriber(captured));

        Instant expiresAt = Instant.now().plusSeconds(600);
        // approvalRequestId travels as a UUID on the broadcast envelope (the
        // builder parses it) — use a real UUID even though the wire field is free-form.
        UUID approvalRequestId = UUID.randomUUID();
        ExecutionPausedPayload.ConversationContinuation continuation = new ExecutionPausedPayload.ConversationContinuation(
                approvalRequestId.toString(), "pending-action-1", "digest-1");
        // §8.6: a CONVERSATION paused payload carries NO suspension block
        // (orchestration-only); expiry lives on the engine-owned approval
        // request (§8.7), not in this frame — expiresAt on the marker stays
        // null here, which is correct protocol behavior.
        ExecutionPausedPayload payload = new ExecutionPausedPayload(
                UUID.randomUUID(), Instant.now(), "APPROVAL_REQUIRED",
                null, null, continuation,
                new ExecutionPausedPayload.Usage("m1", 1L, 2L));

        executionBridge.onConversationPaused(conversation.getId(), host.getId(), payload);

        List<ConversationMessage> messages = conversationMessageRepository
                .findByConversationIdOrderBySequenceNoAsc(conversation.getId());
        assertThat(messages).hasSize(2);
        ConversationMessage approval = messages.get(1);
        assertThat(approval.getRole()).isEqualTo(ConversationMessage.Role.APPROVAL_REQUEST);
        assertThat(approval.getSequenceNo()).isEqualTo(1L);
        assertThat(approval.getPayloadJson()).contains("approvalRequestId");
        assertThat(approval.getPayloadJson()).contains("pendingActionId");
        assertThat(approval.getPayloadJson()).contains("digest-1");

        // Two broadcasts: approval.request + message.complete bridge
        assertThat(captured).hasSize(2);
        assertThat(captured.get(0)).contains("approval.request");
        assertThat(captured.get(1)).contains("message.complete");
        assertThat(captured.get(1)).contains(approval.getId().toString());
    }

    @Test
    void conversationFailureBroadcastsRawFrame() {
        Project project = data.project().named("bridge-fail").create();
        Conversation conversation = data.conversation().inProject(project).create();
        conversationService.appendMessage(conversation.getId(), ConversationMessage.Role.USER, "hi", TEST_ADMIN_ID, null);

        List<String> captured = new ArrayList<>();
        conversationStreamBroker.subscribe(conversation.getId(), new CapturingSubscriber(captured));

        executionBridge.onConversationFailure(conversation.getId(), "{\"type\":\"execution.failed\"}");

        assertThat(captured).containsExactly("{\"type\":\"execution.failed\"}");
    }

    // =================================================================
    // Orchestration bridges
    // =================================================================

    @Test
    void orchestrationCompleteAppliesOutcomeTupleAndDedupsReplay() {
        OrchestrationFixture f = new OrchestrationFixture();

        UUID executionId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("executionId", executionId.toString());
        payload.put("result", Map.of("content", "done"));

        executionBridge.onOrchestrationOutcome(
                f.attempt.getId(), executionId, SessionExecution.State.COMPLETED, payload);

        TaskAttempt attempt = taskAttemptRepository.findById(f.attempt.getId()).orElseThrow();
        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.COMPLETED);
        assertThat(attempt.getResult()).isEqualTo(TaskResult.SUCCESS);
        assertThat(attempt.getOrchestrationResultId()).isEqualTo(executionId);
        assertThat(attempt.getOrchestrationResultDigest()).isNotBlank();

        WorkflowTask refreshed = workflowTaskRepository.findById(f.task.getId()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(refreshed.getResult()).isEqualTo(TaskResult.SUCCESS);
        assertThat(refreshed.getOutput()).containsEntry("result", Map.of("content", "done"));

        // Second call with same executionId must replay (no row version change, digest unchanged).
        Map<String, Object> secondPayload = new LinkedHashMap<>();
        secondPayload.put("executionId", executionId.toString());
        secondPayload.put("result", Map.of("content", "changed"));
        executionBridge.onOrchestrationOutcome(f.attempt.getId(), executionId, SessionExecution.State.COMPLETED, secondPayload);

        WorkflowTask afterReplay = workflowTaskRepository.findById(f.task.getId()).orElseThrow();
        assertThat(afterReplay.getOutput()).containsEntry("result", Map.of("content", "done"));
    }

    @Test
    @Transactional
    void orchestrationPausedSuspensionBlocksSurviveTheStructuredMap() {
        OrchestrationFixture f = new OrchestrationFixture();

        UUID executionId = UUID.randomUUID();
        // H2 truncates timestamptz to micros — truncate to millis so the
        // persisted value round-trips exactly (Plan 2's known pattern).
        Instant expiresAt = Instant.now().plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("executionId", executionId.toString());
        payload.put("suspensionExpiresAt", expiresAt.toString());
        payload.put("suspension", Map.of("expiresAt", expiresAt.toString()));

        executionBridge.onOrchestrationOutcome(
                f.attempt.getId(), executionId, SessionExecution.State.PAUSED, payload);

        TaskAttempt attempt = taskAttemptRepository.findById(f.attempt.getId()).orElseThrow();
        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.PAUSED);

        WorkflowTask refreshed = workflowTaskRepository.findById(f.task.getId()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(TaskStatus.PAUSED);
        assertThat(refreshed.getPauseState()).isEqualTo("ORCH_REVIEW");
        assertThat(refreshed.getApprovalExpiresAt()).isEqualTo(expiresAt);
        assertThat(refreshed.getRequest().getStatus()).isEqualTo(RequestStatus.PAUSED);
    }

    @Test
    @Transactional
    void orchestrationApprovalRequestedPersistsOnTheTask() {
        OrchestrationFixture f = new OrchestrationFixture();

        Instant expiresAt = Instant.now().plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        ExecutionApprovalRequestedPayload.Action action = new ExecutionApprovalRequestedPayload.Action(
                "action-1", "DELETE", "HIGH", "Delete production database", "action-digest");
        ExecutionApprovalRequestedPayload payload = new ExecutionApprovalRequestedPayload(
                UUID.randomUUID(), f.attempt.getId(), "approval-req-2",
                action, "tree-hash", "state-digest", expiresAt);

        executionBridge.onOrchestrationApprovalRequested(f.attempt.getId(), payload);

        WorkflowTask refreshed = workflowTaskRepository.findById(f.task.getId()).orElseThrow();
        assertThat(refreshed.getRequiresApproval()).isTrue();
        assertThat(refreshed.getApprovalStatus()).isEqualTo("PENDING");
        assertThat(refreshed.getApprovalPayload()).containsEntry("approvalRequestId", "approval-req-2");
        assertThat(refreshed.getApprovalPayload()).containsEntry("actionId", "action-1");
        assertThat(refreshed.getApprovalPayload()).containsEntry("riskClass", "HIGH");
        assertThat(refreshed.getApprovalPayload()).containsEntry("digest", "action-digest");
        assertThat(refreshed.getApprovalExpiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void orchestrationEventIsIngestedIdempotently() {
        OrchestrationFixture f = new OrchestrationFixture();

        UUID eventId = UUID.randomUUID();
        ExecutionEventPayload payload = new ExecutionEventPayload(
                UUID.randomUUID(), eventId, "PROGRESS", Instant.now(), Map.of("pct", 50));

        executionBridge.onOrchestrationEvent(f.attempt.getId(), payload, 1L);
        executionBridge.onOrchestrationEvent(f.attempt.getId(), payload, 1L);

        assertThat(executionEventRepository.findBySourceEventId(eventId)).isPresent();
        assertThat(executionEventRepository.findAll()).hasSize(1);
    }

    // =================================================================
    // Helpers
    // =================================================================

    private final class OrchestrationFixture {
        final WorkflowRequest request;
        final WorkflowTask task;
        final TaskAttempt attempt;

        OrchestrationFixture() {
            Project project = data.project().named("orch-" + System.nanoTime()).withRepo("https://x.git", "main").create();
            AgentProfile profile = data.agentProfile().named("orch-profile").create();
            User admin = userRepository.findById(TEST_ADMIN_ID).orElseThrow();

            Workflow wf = new Workflow();
            wf.setProject(project);
            wf.setName("orch-wf-" + System.nanoTime());
            wf.setSteps(List.of());
            wf.setVersion(1);
            wf.setStatus(WorkflowStatus.PUBLISHED);
            wf.setCreatedBy(admin);
            workflowRepository.save(wf);

            WorkflowRequest req = new WorkflowRequest();
            req.setWorkflow(wf);
            req.setWorkflowVersion(1);
            req.setInput(Map.of());
            req.setStatus(RequestStatus.RUNNING);
            req.setBranch("myrmec/orch");
            req.setCreatedBy(admin);
            req.setCreatedAt(Instant.now());
            request = workflowRequestRepository.save(req);

            WorkflowTask t = new WorkflowTask();
            t.setRequest(request);
            t.setStepId("build");
            t.setAgentProfile(profile);
            t.setInput(Map.of());
            t.setStatus(TaskStatus.RUNNING);
            t.setAttempt(1);
            t.setMaxRetries(1);
            task = workflowTaskRepository.save(t);

            TaskAttempt a = task.createAttempt(null);
            attempt = taskAttemptRepository.save(a);
        }
    }

    private static final class CapturingSubscriber implements ConversationSubscriber {
        private final List<String> captured;

        private CapturingSubscriber(List<String> captured) {
            this.captured = captured;
        }

        @Override
        public String id() {
            return "test-sub";
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void send(String jsonFrame) throws IOException {
            captured.add(jsonFrame);
        }
    }
}
