// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionAllocator;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.quota.EnforcementMode;
import ai.myrmec.engine.quota.Quota;
import ai.myrmec.engine.quota.QuotaRepository;
import ai.myrmec.engine.quota.QuotaType;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.websocket.host.HostControlHandshakeInterceptor;
import ai.myrmec.engine.websocket.host.HostControlWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * §8.7 (A4): the execution.policy.update producer + inbound rejection.
 *
 * <ul>
 *   <li>Producer: a usage advance through the accounting seam
 *       ({@link ConversationEventIngestionService} TOKEN_USAGE →
 *       {@link SessionPolicyService#onUsageRecorded}) emits ONE
 *       execution.policy.update frame on the host socket carrying the
 *       accounted {usage, allowance}; a second notification with no
 *       advance is throttled; an ADVANCE emits the next frame (per-batch
 *       throttle, not per-execution lock).</li>
 *   <li>Allowance source: the project's most restrictive quota ceiling;
 *       no quota row → allowance absent (no host-side token limit).</li>
 *   <li>Inbound: the same frame arriving FROM a host on the engine socket
 *       is rejected INVALID_MESSAGE (engine→host frame, §4.3 arm
 *       catalogue) — never a silent ignore.</li>
 * </ul>
 */
class ExecutionPolicyUpdateTest extends IntegrationTestBase {

    @Autowired HostControlWebSocketHandler handler;
    @Autowired SessionAllocator allocator;
    @Autowired SessionRepository sessionRepository;
    @Autowired SessionExecutionRepository executionRepository;
    @Autowired SessionPolicyService sessionPolicyService;
    @Autowired TestDataBuilder data;
    @Autowired QuotaRepository quotaRepository;
    @Autowired ai.myrmec.engine.conversation.ConversationRepository conversationRepository;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private record Host(WebSocketSession session, UUID instanceId) {}

    private Host openedHost() throws Exception {
        AgentHostCreationResult created =
                data.agent().named("a4-host").withMaxAgents(10).create();
        var host = created.agent();

        WebSocketSession session = mock(WebSocketSession.class);
        lenient().when(session.getId()).thenReturn("a4-sock-" + UUID.randomUUID());
        lenient().when(session.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(HostControlHandshakeInterceptor.ATTR_HOST_ID, host.getId());
        attrs.put(HostControlHandshakeInterceptor.ATTR_HOST_NAME, host.getName());
        lenient().when(session.getAttributes()).thenReturn(attrs);

        String open = """
                { "protocolVersion": 1, "messageId": "m-open", "type": "host.open",
                  "sentAt": "%s", "payload": { "instanceNonce": "%s", "hostname": "laptop",
                  "runtimeVersion": "1.8.0", "supportedProtocolVersions": [1], "poolSize": 4,
                  "capabilities": {}, "reportedCapacity": {} } }
                """.formatted(Instant.now(), UUID.randomUUID());
        ((WebSocketHandler) handler).handleMessage(session, new TextMessage(open));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        mapper.readTree(captor.getValue().getPayload());
        return new Host(session, instanceIdOf(host));
    }

    /** The created instance id (captured from host.opened via the row). */
    private UUID instanceIdOf(ai.myrmec.engine.agent.AgentHost host) {
        return instanceRepository.findByAgentHostIdAndStatus(host.getId(),
                ai.myrmec.engine.agent.AgentHostInstance.Status.OPEN).get(0).getId();
    }

    @org.springframework.beans.factory.annotation.Autowired
    ai.myrmec.engine.agent.AgentHostInstanceRepository instanceRepository;

    private record Setup(UUID sessionId, UUID executionId, Host host, Project project,
                         Conversation conversation) {}

    private Setup runningConversationExecution() throws Exception {
        Host host = openedHost();
        Project project = data.project().named("a4-proj").create();

        Conversation conversation = data.conversation().inProject(project).create();
        UUID agentHostId = instanceRepository.findById(host.instanceId())
                .map(i -> i.getAgentHostId()).orElseThrow();
        conversation.setAgentId(agentHostId);
        conversationRepository.save(conversation);

        UUID sessionId = allocator.offer("CONVERSATION", conversation.getId(), "CONVERSATION",
                project.getId(), agentHostId).orElseThrow();

        String accept = """
                { "protocolVersion": 1, "messageId": "m-acc", "type": "session.accept",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s",
                  "payload": { "allocationId": "%s", "sessionId": "%s", "acceptedAt": "%s" } }
                """.formatted(Instant.now(), host.instanceId(), sessionId, sessionId, sessionId, Instant.now());
        ((WebSocketHandler) handler).handleMessage(host.session(), new TextMessage(accept));

        String openedFrame = """
                { "protocolVersion": 1, "messageId": "m-opd", "type": "session.opened",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s",
                  "payload": { "sessionId": "%s", "ready": true, "channelMode": "CONTROL" } }
                """.formatted(Instant.now(), host.instanceId(), sessionId, sessionId);
        ((WebSocketHandler) handler).handleMessage(host.session(), new TextMessage(openedFrame));

        SessionExecution execution = new SessionExecution();
        execution.setSessionId(sessionId);
        execution.setServiceType("CONVERSATION");
        execution.setRequestId(conversation.getId().toString());
        execution.setState(SessionExecution.State.RUNNING);
        execution.setStartedAt(Instant.now());
        execution = executionRepository.save(execution);

        return new Setup(sessionId, execution.getId(), host, project, conversation);
    }

    private java.util.List<JsonNode> replies(WebSocketSession session) {
        return org.mockito.Mockito.mockingDetails(session).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("sendMessage"))
                .map(i -> ((TextMessage) i.getArgument(0)).getPayload())
                .map(p -> { try { return mapper.readTree(p); } catch (Exception e) { throw new RuntimeException(e);} })
                .toList();
    }

    private java.util.List<JsonNode> policyUpdates(WebSocketSession session) {
        return replies(session).stream()
                .filter(n -> "execution.policy.update".equals(n.path("type").asText()))
                .toList();
    }

    private Project tokensQuota(Project project, long limit) {
        Quota quota = new Quota();
        quota.setScopeType(Quota.Scope.PROJECT);
        quota.setScopeId(project.getId());
        quota.setResourceType(Quota.ResourceType.TOKENS);
        quota.setPeriod(Quota.Period.DAILY);
        quota.setLimitAmount(limit);
        quota.setQuotaType(QuotaType.CEILING);
        quota.setEnforcementMode(EnforcementMode.BLOCK);
        quotaRepository.save(quota);
        return project;
    }

    // =================================================================
    // (a) producer: usage advance emits the §8.7 frame with usage + allowance
    // =================================================================

    @Test
    void usageAdvanceEmitsPolicyUpdateWithAccountedUsageAndAllowance() throws Exception {
        Setup setup = runningConversationExecution();
        tokensQuota(setup.project(), 500_000L);

        sessionPolicyService.onUsageRecorded(setup.executionId(), 3L, 8_200L);

        var updates = policyUpdates(setup.host().session());
        assertThat(updates).hasSize(1);
        JsonNode frame = updates.get(0);
        assertThat(frame.path("payload").path("executionId").asText())
                .isEqualTo(setup.executionId().toString());
        assertThat(frame.path("payload").path("usage").path("orchestrationFunctionCalls").asLong())
                .isEqualTo(3L);
        assertThat(frame.path("payload").path("usage").path("totalTokens").asLong())
                .isEqualTo(8_200L);
        // The allowance is the project's most restrictive ceiling.
        assertThat(frame.path("payload").path("allowance").path("maxTokens").asLong())
                .isEqualTo(500_000L);
    }

    // =================================================================
    // (b) throttle: no advance → no frame; a later advance → exactly one frame
    // =================================================================

    @Test
    void updateThrottlesToAccountingBatches() throws Exception {
        Setup setup = runningConversationExecution();
        tokensQuota(setup.project(), 100_000L);

        sessionPolicyService.onUsageRecorded(setup.executionId(), 1L, 1_000L);
        // Same-or-lower totals: no advance → no additional frame.
        sessionPolicyService.onUsageRecorded(setup.executionId(), 1L, 1_000L);
        assertThat(policyUpdates(setup.host().session())).hasSize(1);

        // Advanced totals: exactly ONE more frame (per batch).
        sessionPolicyService.onUsageRecorded(setup.executionId(), 4L, 9_000L);
        var updates = policyUpdates(setup.host().session());
        assertThat(updates).hasSize(2);
        assertThat(updates.get(1).path("payload").path("usage").path("totalTokens").asLong())
                .isEqualTo(9_000L);
    }

    // =================================================================
    // (c) no quota row → allowance absent (no host-side token limit)
    // =================================================================

    @Test
    void unconstrainedSessionOmitsAllowance() throws Exception {
        Setup setup = runningConversationExecution();

        sessionPolicyService.onUsageRecorded(setup.executionId(), 0L, 500L);

        var updates = policyUpdates(setup.host().session());
        assertThat(updates).hasSize(1);
        // The unconstrained session carries no token limit: the field is
        // absent or JSON-null — never a concrete number.
        JsonNode maxTokens = updates.get(0).path("payload").path("allowance").path("maxTokens");
        assertThat(maxTokens.isMissingNode() || maxTokens.isNull()).isTrue();
    }

    // =================================================================
    // (d) terminal execution → no update, throttle state forgotten
    // =================================================================

    @Test
    void terminalExecutionReceivesNoUpdate() throws Exception {
        Setup setup = runningConversationExecution();
        SessionExecution execution = executionRepository.findById(setup.executionId()).orElseThrow();
        execution.setState(SessionExecution.State.COMPLETED);
        execution.setTerminalMessageId("m-term");
        executionRepository.save(execution);

        sessionPolicyService.onUsageRecorded(setup.executionId(), 1L, 100L);

        assertThat(policyUpdates(setup.host().session())).isEmpty();
    }

    // =================================================================
    // (e) inbound execution.policy.update from a host → INVALID_MESSAGE
    // =================================================================

    @Test
    void hostAuthoredPolicyUpdateIsRejected() throws Exception {
        Host host = openedHost();
        UUID executionId = UUID.randomUUID();

        String frame = """
                { "protocolVersion": 1, "messageId": "m-pu-host", "type": "execution.policy.update",
                  "sentAt": "%s", "hostInstanceId": "%s", "executionId": "%s",
                  "payload": { "executionId": "%s", "usage": { "orchestrationFunctionCalls": 1,
                  "totalTokens": 100 }, "allowance": { "maxTokens": 50 } } }
                """.formatted(Instant.now(), host.instanceId(), executionId, executionId);
        ((WebSocketHandler) handler).handleMessage(host.session(), new TextMessage(frame));

        JsonNode error = replies(host.session()).stream()
                .filter(n -> "protocol.error".equals(n.path("type").asText()))
                .reduce((a, b) -> b).orElseThrow();
        assertThat(error.path("payload").path("code").asText()).isEqualTo("INVALID_MESSAGE");
        assertThat(error.path("payload").path("message").asText()).contains("engine→host");
    }
}