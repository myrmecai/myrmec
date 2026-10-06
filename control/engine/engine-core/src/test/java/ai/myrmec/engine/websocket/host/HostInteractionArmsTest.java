// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionAllocator;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.inference.execution.SessionExecution;
import ai.myrmec.engine.inference.execution.SessionExecutionRepository;
import ai.myrmec.engine.inference.execution.interaction.ExecutionInteraction;
import ai.myrmec.engine.inference.execution.interaction.ExecutionInteractionRepository;
import ai.myrmec.engine.inference.execution.interaction.InteractionPolicySnapshotService;
import ai.myrmec.engine.inference.execution.interaction.InteractionProperties;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.user.User;
import ai.myrmec.engine.user.UserRepository;
import ai.myrmec.engine.workflow.ExecutionEvent;
import ai.myrmec.engine.workflow.ExecutionEventRepository;
import ai.myrmec.engine.workflow.RequestStatus;
import ai.myrmec.engine.workflow.TaskAttempt;
import ai.myrmec.engine.workflow.TaskAttemptRepository;
import ai.myrmec.engine.workflow.TaskStatus;
import ai.myrmec.engine.workflow.Workflow;
import ai.myrmec.engine.workflow.WorkflowRequest;
import ai.myrmec.engine.workflow.WorkflowRepository;
import ai.myrmec.engine.workflow.WorkflowRequestRepository;
import ai.myrmec.engine.workflow.WorkflowStatus;
import ai.myrmec.engine.workflow.WorkflowTask;
import ai.myrmec.engine.workflow.WorkflowTaskRepository;
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
 * §22.3 (Task 8): the HostControlWebSocketHandler's interaction-family
 * arms — durable complete/failed settle through
 * ExecutionInteractionService and ack AFTER commit; the ephemeral delta is
 * silently dropped; execution.control.state projects the highest-Sequence
 * observed state onto the 035 columns. Identity/capability failures: a
 * session without the negotiated sessionInteraction capability gets
 * UNSUPPORTED_MESSAGE; a foreign execution gets IDENTITY_MISMATCH.
 */
class HostInteractionArmsTest extends IntegrationTestBase {

    @Autowired HostControlWebSocketHandler handler;
    @Autowired TestDataBuilder data;
    @Autowired SessionRepository sessionRepository;
    @Autowired SessionExecutionRepository executionRepository;
    @Autowired ExecutionInteractionRepository interactionRepository;
    @Autowired ExecutionEventRepository executionEventRepository;
    @Autowired AgentHostInstanceRepository instances;
    @Autowired TaskAttemptRepository taskAttemptRepository;
    @Autowired WorkflowTaskRepository workflowTaskRepository;
    @Autowired WorkflowRequestRepository workflowRequestRepository;
    @Autowired WorkflowRepository workflowRepository;
    @Autowired UserRepository userRepository;

    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new JavaTimeModule());

    private Setup openedHost(String name) throws Exception {
        return openedHost(name, true);
    }

    /** host.open with (or without) the §22.2 sessionInteraction capability. */
    private Setup openedHost(String name, boolean withCapability) throws Exception {
        AgentHostCreationResult created =
                data.agent().named(name).withMaxAgents(10).create();
        AgentHost host = created.agent();

        WebSocketSession session = mock(WebSocketSession.class);
        lenient().when(session.getId()).thenReturn("arms-sock-" + UUID.randomUUID());
        lenient().when(session.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(HostControlHandshakeInterceptor.ATTR_HOST_ID, host.getId());
        lenient().when(session.getAttributes()).thenReturn(attrs);

        String capabilities = withCapability
                ? "{ \"sessionInteraction\": { \"version\": 1, \"temporaryHold\": true } }"
                : "{}";
        String open = """
                { "protocolVersion": 1, "messageId": "m-open", "type": "host.open",
                  "sentAt": "%s", "payload": { "instanceNonce": "%s", "hostname": "laptop",
                  "runtimeVersion": "1.8.0", "supportedProtocolVersions": [1], "poolSize": 4,
                  "capabilities": %s, "reportedCapacity": {} } }
                """.formatted(Instant.now(), UUID.randomUUID(), capabilities);
        ((WebSocketHandler) handler).handleMessage(session, new TextMessage(open));
        return new Setup(session,
                (UUID) session.getAttributes().get(HostControlWebSocketHandler.ATTR_HOST_INSTANCE_ID),
                data.project().named(name + "-proj").create());
    }

    private record Setup(WebSocketSession session, UUID instanceId, Project project) {}

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

    /** A WORKFLOW session + RUNNING execution bound to an orchestration attempt. */
    private record OrchFixture(UUID sessionId, UUID executionId, UUID dispatchId) {}

    private OrchFixture orchestrationExecution(Setup setup) {
        WorkflowRequest request = orchestrationRequest(setup.project());
        var profile = data.agentProfile().named("arms-profile").create();
        WorkflowTask task = new WorkflowTask();
        task.setRequest(request);
        task.setStepId("arms-step");
        task.setAgentProfile(profile);
        task.setInput(Map.of());
        task.setStatus(TaskStatus.RUNNING);
        task.setAttempt(1);
        task.setMaxRetries(1);
        task = workflowTaskRepository.save(task);
        TaskAttempt attempt = taskAttemptRepository.save(task.createAttempt(null));

        Session session = new Session();
        session.setServiceType("WORKFLOW");
        session.setRefId(request.getId());
        session.setProjectId(setup.project().getId());
        session.setKind("ORCHESTRATION_TASK");
        session.setStatus("ACTIVE");
        session.setAllocationState(SessionAllocator.ALLOC_STATE_ACTIVE);
        session.setHostInstanceId(setup.instanceId());
        session = sessionRepository.save(session);

        SessionExecution execution = new SessionExecution();
        execution.setSessionId(session.getId());
        execution.setServiceType("WORKFLOW");
        execution.setRequestId(request.getId().toString());
        execution.setDispatchId(attempt.getId());
        execution.setState(SessionExecution.State.RUNNING);
        execution.setStartedAt(Instant.now());
        execution.setDeadline(Instant.now().plusSeconds(600)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        execution.setInteractionPolicy(InteractionPolicySnapshotService.policyMap(
                InteractionProperties.defaults()));
        execution = executionRepository.save(execution);
        return new OrchFixture(session.getId(), execution.getId(), attempt.getId());
    }

    /** An admitted §3.2 interaction row (the pointer set). */
    private UUID admittedInteraction(OrchFixture fixture) {
        User actor = seedUser("arms-actor");
        ExecutionInteraction interaction = new ExecutionInteraction();
        interaction.setExecutionId(fixture.executionId());
        interaction.setOrdinal(1L);
        interaction.setActorUserId(actor.getId());
        interaction.setClientRequestId(UUID.randomUUID());
        interaction.setRequestDigest("a".repeat(64));
        interaction.setStatus(ai.myrmec.engine.inference.execution.interaction.InteractionStatus.ACCEPTED);
        interaction.setRequestText("who is slow?");
        interaction.setAcceptedAt(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        interaction.setResponseDeadline(Instant.now().plusSeconds(120)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        interaction.setUsageStatus("UNKNOWN");
        interaction = interactionRepository.saveAndFlush(interaction);
        SessionExecution locked = executionRepository.findWithLockById(fixture.executionId())
                .orElseThrow();
        locked.setPendingInteractionId(interaction.getId());
        executionRepository.save(locked);
        return interaction.getId();
    }

    private String interactionFrame(String messageId, String type, OrchFixture fixture,
                                    UUID interactionId, String payloadJson) {
        return """
                { "protocolVersion": 1, "messageId": "%s", "type": "%s",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s", "executionId": "%s",
                  "payload": %s }
                """.formatted(messageId, type, Instant.now(),
                currentInstanceId, fixture.sessionId(), fixture.executionId(), payloadJson);
    }

    private String completePayload(UUID interactionId, String answer) {
        return """
                { "executionId": "%s", "dispatchId": "%s", "interactionId": "%s",
                  "ordinal": 1, "answer": { "text": "%s" },
                  "usage": { "inputTokens": 120, "outputTokens": 30, "modelId": "m" },
                  "usageStatus": "KNOWN", "controlRequestIds": [], "completedAt": "%s" }
                """.formatted(currentExecutionId, currentDispatchId, interactionId, answer,
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    private String failedPayload(UUID interactionId) {
        return """
                { "executionId": "%s", "dispatchId": "%s", "interactionId": "%s",
                  "ordinal": 1,
                  "error": { "errorCode": "MODEL_ERROR", "message": "boom", "retryable": false },
                  "usage": null, "usageStatus": "UNKNOWN", "controlRequestIds": [],
                  "completedAt": "%s" }
                """.formatted(currentExecutionId, currentDispatchId, interactionId,
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    private String deltaPayload(UUID interactionId) {
        return """
                { "executionId": "%s", "dispatchId": "%s", "interactionId": "%s",
                  "index": 0, "text": "partial answer" }
                """.formatted(currentExecutionId, currentDispatchId, interactionId);
    }

    private String statePayload(long stateSequence, String status, String effectiveState,
                                long controlRevision) {
        return """
                { "executionId": "%s", "dispatchId": "%s", "controlRevision": %d,
                  "stateSequence": %d, "status": "%s", "effectiveState": "%s",
                  "reasonCode": "USER_REQUESTED", "changedAt": "%s", "idleResumeAt": null,
                  "safePoint": "BEFORE_MODEL_CALL", "rejectedControlRevision": null,
                  "errorCode": null }
                """.formatted(currentExecutionId, currentDispatchId, controlRevision,
                stateSequence, status, effectiveState,
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    private String statePayloadFor(UUID executionId, UUID dispatchId, long stateSequence) {
        return """
                { "executionId": "%s", "dispatchId": "%s", "controlRevision": 1,
                  "stateSequence": %d, "status": "HELD", "effectiveState": "HELD",
                  "reasonCode": "USER_REQUESTED", "changedAt": "%s", "idleResumeAt": null,
                  "safePoint": "BEFORE_MODEL_CALL", "rejectedControlRevision": null,
                  "errorCode": null }
                """.formatted(executionId, dispatchId, stateSequence,
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    // Per-test current fixture identity (payload builders read them).
    private UUID currentExecutionId;
    private UUID currentDispatchId;
    private UUID currentInstanceId;

    private void bindIdentity(Setup setup, OrchFixture fixture) {
        currentExecutionId = fixture.executionId();
        currentDispatchId = fixture.dispatchId();
        currentInstanceId = setup.instanceId();
    }

    @Test
    @Transactional
    void completeOutcomeSettlesRowAndAcksAfterCommit() throws Exception {
        Setup setup = openedHost("arms-complete");
        OrchFixture fixture = orchestrationExecution(setup);
        bindIdentity(setup, fixture);
        UUID interactionId = admittedInteraction(fixture);

        handler.handleMessage(setup.session(), new TextMessage(interactionFrame(
                "msg-arms-c1", "execution.interaction.complete", fixture, interactionId,
                completePayload(interactionId, "the calm answer"))));

        ExecutionInteraction row = interactionRepository.findById(interactionId).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(
                ai.myrmec.engine.inference.execution.interaction.InteractionStatus.COMPLETED);
        assertThat(row.getAnswerText()).isEqualTo("the calm answer");
        assertThat(row.getTerminalMessageId()).isEqualTo("msg-arms-c1");
        assertThat(row.getUsageStatus()).isEqualTo("KNOWN");
        assertThat(executionRepository.findById(fixture.executionId()).orElseThrow()
                .getPendingInteractionId()).isNull();

        JsonNode ack = lastReply(setup.session());
        assertThat(ack.path("type").asText()).isEqualTo("protocol.ack");
        assertThat(ack.path("payload").path("acknowledgedMessageId").asText())
                .isEqualTo("msg-arms-c1");
        // §3.5: the durable outcome allocated a public stream event.
        List<ExecutionEvent> events = executionEventRepository
                .findByExecutionIdOrderByStreamSequenceAsc(fixture.executionId());
        assertThat(events).isNotEmpty();
        assertThat(events.get(events.size() - 1).getMessage())
                .isEqualTo("execution.interaction.complete");
    }

    @Test
    @Transactional
    void failedOutcomeSetsFailedRowAndAcks() throws Exception {
        Setup setup = openedHost("arms-failed");
        OrchFixture fixture = orchestrationExecution(setup);
        bindIdentity(setup, fixture);
        UUID interactionId = admittedInteraction(fixture);

        handler.handleMessage(setup.session(), new TextMessage(interactionFrame(
                "msg-arms-f1", "execution.interaction.failed", fixture, interactionId,
                failedPayload(interactionId))));

        ExecutionInteraction row = interactionRepository.findById(interactionId).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(
                ai.myrmec.engine.inference.execution.interaction.InteractionStatus.FAILED);
        assertThat(row.getError().get("errorCode")).isEqualTo("MODEL_ERROR");
        assertThat(row.getAnswerText()).isNull();
        JsonNode ack = lastReply(setup.session());
        assertThat(ack.path("type").asText()).isEqualTo("protocol.ack");
    }

    @Test
    @Transactional
    void wrongDispatchIdIsIdentityMismatchNothingSettled() throws Exception {
        Setup setup = openedHost("arms-baddispatch");
        OrchFixture fixture = orchestrationExecution(setup);
        bindIdentity(setup, fixture);
        UUID interactionId = admittedInteraction(fixture);

        // The payload names a DIFFERENT dispatchId than the admitted execution.
        String payload = """
                { "executionId": "%s", "dispatchId": "%s", "interactionId": "%s",
                  "ordinal": 1, "answer": { "text": "nope" }, "usage": null,
                  "usageStatus": "UNKNOWN", "controlRequestIds": [], "completedAt": "%s" }
                """.formatted(fixture.executionId(), UUID.randomUUID(), interactionId,
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        handler.handleMessage(setup.session(), new TextMessage(interactionFrame(
                "msg-arms-bad", "execution.interaction.complete", fixture, interactionId,
                payload)));

        JsonNode error = lastReply(setup.session());
        assertThat(error.path("type").asText()).isEqualTo("protocol.error");
        assertThat(error.path("payload").path("code").asText())
                .isEqualTo(HostProtocol.IDENTITY_MISMATCH);
        ExecutionInteraction row = interactionRepository.findById(interactionId).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(
                ai.myrmec.engine.inference.execution.interaction.InteractionStatus.ACCEPTED);
        // Nothing acked for the refused frame.
        assertThat(replies(setup.session()))
                .noneMatch(n -> "protocol.ack".equals(n.path("type").asText()));
    }

    @Test
    @Transactional
    void sessionWithoutCapabilityGetsUnsupportedMessage() throws Exception {
        // The host opened WITH the capability (handshake fail-closes without
        // it), then the LIVE connection's advertised capability is replaced
        // with an EMPTY map — simulating a degraded/re-adopted instance. The
        // arm gate must fail closed (UNSUPPORTED_MESSAGE).
        Setup setup = openedHost("arms-nocap");
        OrchFixture fixture = orchestrationExecution(setup);
        bindIdentity(setup, fixture);
        UUID interactionId = admittedInteraction(fixture);

        connectionManager().register(setup.instanceId(), setup.session(), Map.of());

        handler.handleMessage(setup.session(), new TextMessage(interactionFrame(
                "msg-arms-nocap", "execution.interaction.complete", fixture, interactionId,
                completePayload(interactionId, "never settles"))));

        JsonNode error = lastReply(setup.session());
        assertThat(error.path("type").asText()).isEqualTo("protocol.error");
        assertThat(error.path("payload").path("code").asText())
                .isEqualTo(HostProtocol.UNSUPPORTED_MESSAGE);
        ExecutionInteraction row = interactionRepository.findById(interactionId).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(
                ai.myrmec.engine.inference.execution.interaction.InteractionStatus.ACCEPTED);
    }

    /** The handler's connection registry (the capability-map owner). */
    private HostConnectionManager connectionManager() {
        return handler.getConnectionManager();
    }

    @Test
    @Transactional
    void deltaIsSilentlyDroppedWithNoDurableRecordAndNoAck() throws Exception {
        Setup setup = openedHost("arms-delta");
        OrchFixture fixture = orchestrationExecution(setup);
        bindIdentity(setup, fixture);
        UUID interactionId = admittedInteraction(fixture);
        long streamBefore = executionRepository.findById(fixture.executionId()).orElseThrow()
                .getStreamSequence();
        int repliesBefore = replies(setup.session()).size();

        handler.handleMessage(setup.session(), new TextMessage(interactionFrame(
                "msg-arms-d1", "execution.interaction.delta", fixture, interactionId,
                deltaPayload(interactionId))));

        // §22.3: ephemeral — no reply beyond the host.opened handshake, no
        // durable row, no stream event, and the interaction stays ACCEPTED
        // (the durable complete owns settling).
        assertThat(replies(setup.session())).hasSize(repliesBefore);
        assertThat(interactionRepository.findById(interactionId).orElseThrow().getStatus())
                .isEqualTo(ai.myrmec.engine.inference.execution.interaction.InteractionStatus.ACCEPTED);
        assertThat(executionRepository.findById(fixture.executionId()).orElseThrow()
                .getStreamSequence()).isEqualTo(streamBefore);
        assertThat(interactionRepository.findById(interactionId).orElseThrow().getAnswerText())
                .isNull();

        // A malformed delta is silently dropped too (best-effort §22.3).
        handler.handleMessage(setup.session(), new TextMessage("""
                { "protocolVersion": 1, "messageId": "msg-arms-d2", "type": "execution.interaction.delta",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s", "executionId": "%s",
                  "payload": { "executionId": "%s", "dispatchId": "%s", "interactionId": "%s",
                  "index": 0, "text": "" } }
                """.formatted(Instant.now(), setup.instanceId(), fixture.sessionId(),
                fixture.executionId(), fixture.executionId(), fixture.dispatchId(), interactionId)));
        assertThat(replies(setup.session())).hasSize(repliesBefore);
    }

    @Test
    @Transactional
    void controlStateProjectsColumnsUnderRowLockAndAcks() throws Exception {
        Setup setup = openedHost("arms-state");
        OrchFixture fixture = orchestrationExecution(setup);
        bindIdentity(setup, fixture);

        handler.handleMessage(setup.session(), new TextMessage(interactionFrame(
                "msg-arms-s1", "execution.control.state", fixture, null,
                statePayload(2, "HELD", "HELD", 1))));

        SessionExecution row = executionRepository.findById(fixture.executionId()).orElseThrow();
        assertThat(row.getControlStateSequence()).isEqualTo(2L);
        assertThat(row.getAcceptedControlRevision()).isEqualTo(1L);
        assertThat(row.getHoldState()).isEqualTo("HELD");
        assertThat(row.getHoldChangedAt()).isNotNull();
        JsonNode ack = lastReply(setup.session());
        assertThat(ack.path("type").asText()).isEqualTo("protocol.ack");
        assertThat(ack.path("payload").path("acknowledgedMessageId").asText())
                .isEqualTo("msg-arms-s1");
    }

    @Test
    @Transactional
    void staleControlStateIsANoOpProjectionButStillAcknowledged() throws Exception {
        Setup setup = openedHost("arms-stale");
        OrchFixture fixture = orchestrationExecution(setup);
        bindIdentity(setup, fixture);
        // A NEWER report lands first (sequence 5).
        handler.handleMessage(setup.session(), new TextMessage(interactionFrame(
                "msg-arms-s2", "execution.control.state", fixture, null,
                statePayload(5, "RUNNING", "RUNNING", 1))));
        long latest = executionRepository.findById(fixture.executionId()).orElseThrow()
                .getControlStateSequence();

        // A STALE lower sequence arrives — the projection must not regress.
        handler.handleMessage(setup.session(), new TextMessage(interactionFrame(
                "msg-arms-s3", "execution.control.state", fixture, null,
                statePayload(2, "HELD", "HELD", 9))));

        SessionExecution row = executionRepository.findById(fixture.executionId()).orElseThrow();
        assertThat(row.getControlStateSequence()).isEqualTo(latest);
        assertThat(row.getHoldState()).isEqualTo("RUNNING");
        // acceptedControlRevision never advanced from the REJECTED-eligible
        // stale frame's controlRevision 9 (highest-wins only on newer frames).
        assertThat(row.getAcceptedControlRevision()).isEqualTo(1L);
        // The stale frame IS acknowledged (durable no-op contract): two —
        // one per control.state frame (the handshake's host.opened reply is
        // not an ack).
        var acks = replies(setup.session()).stream()
                .filter(n -> "protocol.ack".equals(n.path("type").asText())).toList();
        assertThat(acks).hasSize(2);
    }

    @Test
    @Transactional
    void controlStateForForeignSessionIsIdentityMismatch() throws Exception {
        Setup owner = openedHost("arms-state-owner");
        Setup outsider = openedHost("arms-state-outside");
        OrchFixture fixture = orchestrationExecution(owner);
        bindIdentity(owner, fixture);

        // The OUTSIDER's connection reports state for the OWNER's execution
        // (its own payload id pair points at the owner's execution).
        String payload = statePayloadFor(fixture.executionId(), fixture.dispatchId(), 3);
        String frame = """
                { "protocolVersion": 1, "messageId": "msg-arms-foreign", "type": "execution.control.state",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s", "executionId": "%s",
                  "payload": %s }
                """.formatted(Instant.now(), outsider.instanceId(), fixture.sessionId(),
                fixture.executionId(), payload);
        handler.handleMessage(outsider.session(), new TextMessage(frame));

        JsonNode error = lastReply(outsider.session());
        assertThat(error.path("type").asText()).isEqualTo("protocol.error");
        assertThat(error.path("payload").path("code").asText())
                .isEqualTo(HostProtocol.IDENTITY_MISMATCH);
        // No projection on the owner's execution.
        assertThat(executionRepository.findById(fixture.executionId()).orElseThrow()
                .getControlStateSequence()).isZero();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private User seedUser(String suffix) {
        User user = new User();
        user.setEmail("arms-" + suffix + "-" + System.nanoTime() + "@test.local");
        user.setName("Arms " + suffix);
        user.setPasswordHash("$2a$10$dummy");
        user.setProviderCode(ai.myrmec.engine.user.AuthenticationProvider.LOCAL_CODE);
        user.setIsActive(true);
        user.setIsSystem(false);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user);
    }

    private WorkflowRequest orchestrationRequest(Project project) {
        Workflow wf = new Workflow();
        wf.setProject(project);
        wf.setName("arms-wf-" + System.nanoTime());
        wf.setSteps(List.of());
        wf.setVersion(1);
        wf.setStatus(WorkflowStatus.PUBLISHED);
        wf.setCreatedBy(userRepository.findById(TEST_ADMIN_ID).orElseThrow());
        workflowRepository.save(wf);

        WorkflowRequest req = new WorkflowRequest();
        req.setWorkflow(wf);
        req.setWorkflowVersion(1);
        req.setInput(Map.of());
        req.setStatus(RequestStatus.RUNNING);
        req.setBranch("myrmec/arms");
        req.setCreatedBy(userRepository.findById(TEST_ADMIN_ID).orElseThrow());
        req.setCreatedAt(Instant.now());
        return workflowRequestRepository.save(req);
    }
}
