// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.stream.ConversationStreamBroker;
import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionAllocator;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.quota.Quota;
import ai.myrmec.engine.quota.QuotaConsumptionRepository;
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
 * #139 (§8.4/§12.3): the conversation execution.event durable sink.
 * TOKEN_USAGE events persist as {@code execution_events} rows with
 * {@code kind=CONVERSATION} ({@code task_id} null) AND attribute tokens to
 * the conversation/project consumption seam; replays of the same
 * {@code sourceEventId} don't double-insert and still ack; an ingestion
 * failure stays unacked (the host resends); unknown executionIds are
 * rejected on the same error path as the orchestration validation.
 * Frames are driven straight into the handler with stub sessions —
 * the house pattern from ExecutionLifecycleFlowTest.
 */
class ConversationEventIngestionTest extends IntegrationTestBase {

    @Autowired HostControlWebSocketHandler handler;
    @Autowired SessionAllocator allocator;
    @Autowired SessionRepository sessionRepository;
    @Autowired SessionExecutionRepository executionRepository;
    @Autowired TestDataBuilder data;
    @Autowired QuotaRepository quotaRepository;
    @Autowired QuotaConsumptionRepository quotaConsumptionRepository;
    @Autowired ai.myrmec.engine.conversation.ConversationRepository conversationRepository;
    @Autowired ai.myrmec.engine.assistant.AssistantService assistantService;
    @Autowired ai.myrmec.engine.assistant.AssistantVersionService assistantVersionService;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private record Host(WebSocketSession session, UUID instanceId) {}

    private Host openedHost() throws Exception {
        AgentHostCreationResult created =
                data.agent().named("ce-host").withMaxAgents(10).create();
        var host = created.agent();

        WebSocketSession session = mock(WebSocketSession.class);
        lenient().when(session.getId()).thenReturn("ce-sock-" + UUID.randomUUID());
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
        JsonNode opened = mapper.readTree(captor.getValue().getPayload());
        UUID instanceId = UUID.fromString(
                opened.path("payload").path("hostInstanceId").asText());
        return new Host(session, instanceId);
    }

    private record Setup(UUID sessionId, UUID executionId, Host host, Conversation conversation,
                         Project project) {}

    /** A conversation execution in RUNNING state on a live host (house pattern). */
    private Setup runningConversationExecution() throws Exception {
        Host host = openedHost();
        Project project = data.project().named("ce-proj").create();
        AgentProfile profile = data.agentProfile().named("ce-profile").create();

        Conversation conversation = data.conversation().inProject(project).create();
        conversation.setAgentId(hostInstanceIdToAgentId(host));
        conversationRepository.save(conversation);

        UUID sessionId = allocator.offer("CONVERSATION", conversation.getId(), "CONVERSATION",
                project.getId(), hostInstanceIdToAgentId(host)).orElseThrow();

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

        return new Setup(sessionId, execution.getId(), host, conversation, project);
    }

    /** The host instance row is resolved back to its AgentHost id. */
    private UUID hostInstanceIdToAgentId(Host host) {
        return agentHostInstanceRepository.findById(host.instanceId())
                .map(i -> i.getAgentHostId()).orElseThrow();
    }

    private void sendEvent(Setup setup, String messageId, long sequence, UUID eventId,
                           String eventType, Map<String, Object> data) throws Exception {
        String dataJson = mapper.writeValueAsString(data == null ? Map.of() : data);
        String payload = """
                { "executionId": "%s", "eventId": "%s", "eventType": "%s",
                  "occurredAt": "%s", "data": %s }
                """.formatted(setup.executionId(), eventId, eventType,
                Instant.now(), dataJson);
        String frame = """
                { "protocolVersion": 1, "messageId": "%s", "type": "execution.event",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s", "executionId": "%s",
                  "sequence": %d, "payload": %s }
                """.formatted(messageId, Instant.now(), setup.host().instanceId(),
                setup.sessionId(), setup.executionId(), sequence, payload);
        ((WebSocketHandler) handler).handleMessage(setup.host().session(), new TextMessage(frame));
    }

    private java.util.List<JsonNode> replies(WebSocketSession session) {
        return org.mockito.Mockito.mockingDetails(session).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("sendMessage"))
                .map(i -> ((TextMessage) i.getArgument(0)).getPayload())
                .map(p -> { try { return mapper.readTree(p); } catch (Exception e) { throw new RuntimeException(e);} })
                .toList();
    }

    private JsonNode ackForMessageId(WebSocketSession session, String messageId) {
        for (JsonNode reply : replies(session)) {
            if ("protocol.ack".equals(reply.path("type").asText())
                    && messageId.equals(reply.path("payload").path("acknowledgedMessageId").asText())) {
                return reply;
            }
        }
        return null;
    }

    private long consumptionFor(UUID scopeId) {
        return quotaConsumptionRepository.findAll().stream()
                .filter(c -> quotaRepository.findById(c.getQuotaId())
                        .map(q -> scopeId.equals(q.getScopeId()))
                        .orElse(false))
                .mapToLong(ai.myrmec.engine.quota.QuotaConsumption::getAmountUsed)
                .sum();
    }

    private Quota projectTokensQuota(Project project) {
        Quota quota = new Quota();
        quota.setScopeType(Quota.Scope.PROJECT);
        quota.setScopeId(project.getId());
        quota.setResourceType(Quota.ResourceType.TOKENS);
        quota.setPeriod(Quota.Period.DAILY);
        quota.setLimitAmount(1_000_000L);
        quota.setQuotaType(QuotaType.CEILING);
        quota.setEnforcementMode(ai.myrmec.engine.quota.EnforcementMode.BLOCK);
        return quotaRepository.save(quota);
    }

    // =================================================================
    // (a) TOKEN_USAGE persists AND attributes tokens
    // =================================================================

    @Test
    void conversationTokenUsagePersistsAndAttributesConsumption() throws Exception {
        Setup setup = runningConversationExecution();
        projectTokensQuota(setup.project());

        UUID eventId = UUID.randomUUID();
        sendEvent(setup, "m-usage-1", 1L, eventId, "TOKEN_USAGE",
                Map.of("model", "m1", "promptTokens", 10, "completionTokens", 15, "totalTokens", 25));

        // Row persisted with kind=CONVERSATION and no task.
        var row = executionEventRepository.findBySourceEventId(eventId).orElseThrow();
        assertThat(row.getKind()).isEqualTo("CONVERSATION");
        assertThat(row.getTaskId()).isNull();
        assertThat(row.getAttemptId()).isNull();
        assertThat(row.getEventType()).isEqualTo(ai.myrmec.engine.workflow.EventType.TOKEN_USAGE);
        assertThat(row.getData()).containsEntry("totalTokens", 25);
        // Cursor advanced — the frame was acked.
        assertThat(sessionRepository.findById(setup.sessionId()).orElseThrow().getHighestContiguousSequence())
                .isEqualTo(1L);
        assertThat(ackForMessageId(setup.host().session(), "m-usage-1")).isNotNull();

        // Tokens attributed to the project quota consumption (no assistant pin on
        // this bare conversation fixture — the project path is the fallback).
        assertThat(consumptionFor(setup.project().getId())).isEqualTo(25L);
    }

    // =================================================================
    // (b) replay of the same sourceEventId doesn't double-insert, still acks
    // =================================================================

    @Test
    void replayOfSameSourceEventIdIsIdempotentAndStillAcks() throws Exception {
        Setup setup = runningConversationExecution();
        UUID eventId = UUID.randomUUID();

        sendEvent(setup, "m-replay-1", 1L, eventId, "LOG",
                Map.of("level", "INFO", "message", "first"));
        sendEvent(setup, "m-replay-2", 1L, eventId, "LOG",
                Map.of("level", "INFO", "message", "first"));

        var replayed = executionEventRepository.findBySourceEventId(eventId).orElseThrow();
        long rowsForEvent = executionEventRepository.findAll().stream()
                .filter(e -> eventId.equals(e.getSourceEventId())).count();
        assertThat(rowsForEvent).isEqualTo(1L);
        assertThat(replayed.getMessage()).isEqualTo("LOG");

        // Both frames acked — replay advances the same cursor.
        assertThat(ackForMessageId(setup.host().session(), "m-replay-1")).isNotNull();
        assertThat(ackForMessageId(setup.host().session(), "m-replay-2")).isNotNull();
        assertThat(sessionRepository.findById(setup.sessionId()).orElseThrow().getHighestContiguousSequence())
                .isEqualTo(1L);
    }

    // =================================================================
    // (c) ingestion failure → no ack (host resend path)
    // =================================================================

    @Test
    void ingestionFailureLeavesFrameUnacked() throws Exception {
        Setup setup = runningConversationExecution();

        // Swap in a throwing quota engine? No — force the failure one level up:
        // a conflicting-duplicate replay (same eventId, DIFFERENT payload) makes
        // the sink throw IllegalStateException AFTER the correlation checks, so
        // the handler must skip the ack.
        UUID eventId = UUID.randomUUID();
        sendEvent(setup, "m-fail-1", 1L, eventId, "LOG",
                Map.of("message", "original"));
        sendEvent(setup, "m-fail-2", 2L, eventId, "LOG",
                Map.of("message", "conflicting-payload"));

        assertThat(ackForMessageId(setup.host().session(), "m-fail-1")).isNotNull();
        assertThat(ackForMessageId(setup.host().session(), "m-fail-2")).isNull();
        JsonNode error = replies(setup.host().session())
                .get(replies(setup.host().session()).size() - 1);
        assertThat(error.path("type").asText()).isEqualTo("protocol.error");
        // Cursor did NOT advance past sequence 1.
        assertThat(sessionRepository.findById(setup.sessionId()).orElseThrow().getHighestContiguousSequence())
                .isEqualTo(1L);
    }

    // =================================================================
    // (d) unknown executionId → rejected like the orchestration validation
    // =================================================================

    @Test
    void unknownExecutionIdIsRejected() throws Exception {
        Host host = openedHost();
        UUID bogusExecutionId = UUID.randomUUID();

        String frame = """
                { "protocolVersion": 1, "messageId": "m-unknown", "type": "execution.event",
                  "sentAt": "%s", "hostInstanceId": "%s", "executionId": "%s", "sequence": 1,
                  "payload": { "executionId": "%s", "eventId": "%s", "eventType": "LOG",
                  "occurredAt": "%s", "data": {} } }
                """.formatted(Instant.now(), host.instanceId(), bogusExecutionId,
                bogusExecutionId, UUID.randomUUID(), Instant.now());
        ((WebSocketHandler) handler).handleMessage(host.session(), new TextMessage(frame));

        JsonNode error = replies(host.session()).get(replies(host.session()).size() - 1);
        assertThat(error.path("type").asText()).isEqualTo("protocol.error");
        assertThat(error.path("payload").path("code").asText()).isEqualTo("INVALID_STATE");
        assertThat(ackForMessageId(host.session(), "m-unknown")).isNull();
    }

    // =================================================================
    // extra: unknown eventType tolerated (LOG + data note), §8.4 union
    // =================================================================

    @Test
    void unknownEventTypePersistsAsLogWithNote() throws Exception {
        Setup setup = runningConversationExecution();
        UUID eventId = UUID.randomUUID();
        sendEvent(setup, "m-unknown-type", 1L, eventId, "CHECKPOINT",
                Map.of("marker", "x"));

        var row = executionEventRepository.findBySourceEventId(eventId).orElseThrow();
        assertThat(row.getEventType()).isEqualTo(ai.myrmec.engine.workflow.EventType.LOG);
        assertThat(row.getData()).containsEntry("unknownEventType", "CHECKPOINT");
        assertThat(ackForMessageId(setup.host().session(), "m-unknown-type")).isNotNull();
    }
}