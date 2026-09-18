// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationMessage;
import ai.myrmec.engine.conversation.ConversationMessageRepository;
import ai.myrmec.engine.conversation.ConversationService;
import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionAllocator;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.inference.execution.SessionExecution;
import ai.myrmec.engine.inference.execution.SessionExecutionRepository;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.workflow.TaskAttempt;
import ai.myrmec.engine.workflow.TaskAttemptRepository;
import ai.myrmec.engine.workflow.Workflow;
import ai.myrmec.engine.workflow.WorkflowRequest;
import ai.myrmec.engine.workflow.WorkflowStatus;
import ai.myrmec.engine.workflow.WorkflowTask;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * §5/§8.7 execution.approval.requested inbound arm: the orchestration arm
 * persists the approval on the WorkflowTask through the execution's
 * dispatchId; the conversation arm persists the §8.7 approval through the
 * transcript seam (ConversationService.appendApprovalRequest); a frame whose
 * execution belongs to a different session is IDENTITY_MISMATCH; the frame
 * is acknowledged (§12.3) after the persist.
 */
class HostExecutionApprovalRequestedTest extends IntegrationTestBase {

    @Autowired HostControlWebSocketHandler handler;
    @Autowired TestDataBuilder data;
    @Autowired SessionRepository sessionRepository;
    @Autowired SessionExecutionRepository executionRepository;
    @Autowired ConversationService conversationService;
    @Autowired ConversationMessageRepository conversationMessageRepository;
    @Autowired TaskAttemptRepository taskAttemptRepository;
    @Autowired ai.myrmec.engine.workflow.WorkflowRepository workflowRepository;
    @Autowired ai.myrmec.engine.workflow.WorkflowRequestRepository workflowRequestRepository;
    @Autowired ai.myrmec.engine.workflow.WorkflowTaskRepository workflowTaskRepository;
    @Autowired ai.myrmec.engine.user.UserRepository userRepository;

    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new JavaTimeModule());

    private record Setup(WebSocketSession session, UUID instanceId, Project project) {}

    private Setup openedHost(String name) throws Exception {
        AgentHostCreationResult created =
                data.agent().named(name).withMaxAgents(10).create();
        AgentHost host = created.agent();

        WebSocketSession session = mock(WebSocketSession.class);
        lenient().when(session.getId()).thenReturn("appr-sock-" + UUID.randomUUID());
        lenient().when(session.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(HostControlHandshakeInterceptor.ATTR_HOST_ID, host.getId());
        lenient().when(session.getAttributes()).thenReturn(attrs);

        String open = """
                { "protocolVersion": 1, "messageId": "m-open", "type": "host.open",
                  "sentAt": "%s", "payload": { "instanceNonce": "%s", "hostname": "laptop",
                  "runtimeVersion": "1.8.0", "supportedProtocolVersions": [1], "poolSize": 4,
                  "capabilities": {}, "reportedCapacity": {} } }
                """.formatted(Instant.now(), UUID.randomUUID());
        ((WebSocketHandler) handler).handleMessage(session, new TextMessage(open));
        return new Setup(session,
                (UUID) session.getAttributes().get(HostControlWebSocketHandler.ATTR_HOST_INSTANCE_ID),
                data.project().named(name + "-proj").create());
    }

    private List<JsonNode> replies(WebSocketSession session) {
        return org.mockito.Mockito.mockingDetails(session).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("sendMessage"))
                .map(i -> ((TextMessage) i.getArgument(0)).getPayload())
                .map(p -> { try { return mapper.readTree(p); } catch (Exception e) { throw new RuntimeException(e);} })
                .toList();
    }

    private JsonNode lastReply(WebSocketSession session) {
        var r = replies(session);
        return r.get(r.size() - 1);
    }

    /** An ACTIVE conversation session with one execution. */
    private record ConvFixture(UUID sessionId, UUID executionId, Conversation conversation) {}

    private ConvFixture conversationSession(Setup setup) {
        Conversation conversation = data.conversation().inProject(setup.project()).create();

        Session session = new Session();
        session.setServiceType("CONVERSATION");
        session.setRefId(conversation.getId());
        session.setProjectId(setup.project().getId());
        session.setHostInstanceId(setup.instanceId());
        session.setAllocationState(SessionAllocator.ALLOC_STATE_ACTIVE);
        session = sessionRepository.save(session);

        SessionExecution execution = new SessionExecution();
        execution.setSessionId(session.getId());
        execution.setServiceType("CONVERSATION");
        execution.setRequestId(conversation.getId().toString());
        execution.setState(SessionExecution.State.RUNNING);
        execution = executionRepository.save(execution);
        return new ConvFixture(session.getId(), execution.getId(), conversation);
    }

    @Test
    @Transactional
    void conversationApprovalRequestedPersistsAndIsAcknowledged() throws Exception {
        Setup setup = openedHost("appr-conv");
        ConvFixture fixture = conversationSession(setup);

        String frame = """
                { "protocolVersion": 1, "messageId": "m-appr", "type": "execution.approval.requested",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s", "executionId": "%s",
                  "payload": { "executionId": "%s", "approvalRequestId": "approval-req-x",
                  "action": { "actionId": "call-7", "type": "RUN_COMMAND", "riskClass": "DESTRUCTIVE",
                  "summary": "wipe the build dir", "digest": "d-7" },
                  "expiresAt": "%s" } }
                """.formatted(Instant.now(), setup.instanceId(), fixture.sessionId(),
                fixture.executionId(), fixture.executionId(), Instant.now().plusSeconds(300));

        ((WebSocketHandler) handler).handleMessage(setup.session(), new TextMessage(frame));

        // Persisted through the transcript seam BEFORE the ack.
        var messages = conversationMessageRepository
                .findByConversationIdOrderBySequenceNoAsc(fixture.conversation().getId());
        assertThat(messages).hasSize(1);
        ConversationMessage approval = messages.get(0);
        assertThat(approval.getRole()).isEqualTo(ConversationMessage.Role.APPROVAL_REQUEST);
        assertThat(approval.getPayloadJson()).contains("approval-req-x");
        assertThat(approval.getPayloadJson()).contains("call-7");

        JsonNode ack = lastReply(setup.session());
        assertThat(ack.path("type").asText()).isEqualTo("protocol.ack");
        assertThat(ack.path("payload").path("acknowledgedMessageId").asText()).isEqualTo("m-appr");
    }

    @Test
    @Transactional
    void wrongInstanceCorrelationIsIdentityMismatch() throws Exception {
        Setup owner = openedHost("appr-owner");
        Setup outsider = openedHost("appr-outside");
        ConvFixture fixture = conversationSession(owner);

        // The outsider's connection sends an approval frame for the OWNER's
        // execution — the execution's session is pinned to a different instance.
        String frame = """
                { "protocolVersion": 1, "messageId": "m-appr-bad", "type": "execution.approval.requested",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s", "executionId": "%s",
                  "payload": { "executionId": "%s", "approvalRequestId": "approval-req-bad" } }
                """.formatted(Instant.now(), outsider.instanceId(), fixture.sessionId(),
                fixture.executionId(), fixture.executionId());

        ((WebSocketHandler) handler).handleMessage(outsider.session(), new TextMessage(frame));

        JsonNode error = lastReply(outsider.session());
        assertThat(error.path("type").asText()).isEqualTo("protocol.error");
        assertThat(error.path("payload").path("code").asText())
                .isEqualTo(HostProtocol.IDENTITY_MISMATCH);
        // Nothing persisted.
        assertThat(conversationMessageRepository
                .findByConversationIdOrderBySequenceNoAsc(fixture.conversation().getId()))
                .isEmpty();
    }

    @Test
    @Transactional
    void orchestrationApprovalRequestedPersistsOnTheTask() throws Exception {
        Setup setup = openedHost("appr-orch");
        var f = orchestrationSession(setup);

        Instant expiresAt = Instant.now().plusSeconds(600)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        String frame = """
                { "protocolVersion": 1, "messageId": "m-appr-orch", "type": "execution.approval.requested",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s", "executionId": "%s",
                  "payload": { "executionId": "%s", "dispatchId": "%s",
                  "approvalRequestId": "approval-orch-1",
                  "action": { "actionId": "a-1", "type": "CHECKPOINT", "riskClass": "HIGH",
                  "summary": "Delete production database", "digest": "d-1" },
                  "expiresAt": "%s" } }
                """.formatted(Instant.now(), setup.instanceId(), f.sessionId(),
                f.executionId(), f.executionId(), f.attemptId(), expiresAt);

        ((WebSocketHandler) handler).handleMessage(setup.session(), new TextMessage(frame));

        ai.myrmec.engine.workflow.WorkflowTask refreshed =
                workflowTaskRepository.findById(f.taskId()).orElseThrow();
        assertThat(refreshed.getRequiresApproval()).isTrue();
        assertThat(refreshed.getApprovalStatus()).isEqualTo("PENDING");
        assertThat(refreshed.getApprovalPayload()).containsEntry("approvalRequestId", "approval-orch-1");
        assertThat(refreshed.getApprovalPayload()).containsEntry("actionId", "a-1");
        assertThat(refreshed.getApprovalExpiresAt()).isEqualTo(expiresAt);

        JsonNode ack = lastReply(setup.session());
        assertThat(ack.path("type").asText()).isEqualTo("protocol.ack");
        assertThat(ack.path("payload").path("acknowledgedMessageId").asText()).isEqualTo("m-appr-orch");
    }

    private record OrchFixture(UUID sessionId, UUID executionId, UUID taskId, UUID attemptId) {}

    /** A WORKFLOW session + running execution bound to an orchestration attempt. */
    private OrchFixture orchestrationSession(Setup setup) {
        ai.myrmec.engine.workflow.WorkflowRequest request = orchestrationRequest(setup.project());
        ai.myrmec.engine.agent.AgentProfile profile = data.agentProfile().named("appr-profile").create();
        ai.myrmec.engine.workflow.WorkflowTask task = new ai.myrmec.engine.workflow.WorkflowTask();
        task.setRequest(request);
        task.setStepId("build");
        task.setAgentProfile(profile);
        task.setInput(Map.of());
        task.setStatus(ai.myrmec.engine.workflow.TaskStatus.RUNNING);
        task.setAttempt(1);
        task.setMaxRetries(1);
        task = workflowTaskRepository.save(task);
        TaskAttempt attempt = taskAttemptRepository.save(task.createAttempt(null));

        Session session = new Session();
        session.setServiceType("WORKFLOW");
        session.setRefId(request.getId());
        session.setProjectId(setup.project().getId());
        session.setHostInstanceId(setup.instanceId());
        session.setAllocationState(SessionAllocator.ALLOC_STATE_ACTIVE);
        session = sessionRepository.save(session);

        SessionExecution execution = new SessionExecution();
        execution.setSessionId(session.getId());
        execution.setServiceType("WORKFLOW");
        execution.setRequestId(request.getId().toString());
        execution.setDispatchId(attempt.getId());
        execution.setState(SessionExecution.State.RUNNING);
        execution = executionRepository.save(execution);
        return new OrchFixture(session.getId(), execution.getId(), task.getId(), attempt.getId());
    }

    /** The minimal request/workflow chain the orchestration approval arm needs. */
    private ai.myrmec.engine.workflow.WorkflowRequest orchestrationRequest(Project project) {
        ai.myrmec.engine.workflow.Workflow wf = new ai.myrmec.engine.workflow.Workflow();
        wf.setProject(project);
        wf.setName("appr-wf-" + System.nanoTime());
        wf.setSteps(List.of());
        wf.setVersion(1);
        wf.setStatus(WorkflowStatus.PUBLISHED);
        wf.setCreatedBy(userRepository.findById(TEST_ADMIN_ID).orElseThrow());
        workflowRepository.save(wf);

        ai.myrmec.engine.workflow.WorkflowRequest req = new ai.myrmec.engine.workflow.WorkflowRequest();
        req.setWorkflow(wf);
        req.setWorkflowVersion(1);
        req.setInput(Map.of());
        req.setStatus(ai.myrmec.engine.workflow.RequestStatus.RUNNING);
        req.setBranch("myrmec/appr");
        req.setCreatedBy(userRepository.findById(TEST_ADMIN_ID).orElseThrow());
        req.setCreatedAt(Instant.now());
        return workflowRequestRepository.save(req);
    }
}