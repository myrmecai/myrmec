// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationMessageRepository;
import ai.myrmec.engine.conversation.ConversationService;
import ai.myrmec.engine.conversation.stream.ConversationStreamBroker;
import ai.myrmec.engine.conversation.stream.ConversationSubscriber;
import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionAllocator;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.websocket.host.HostControlHandshakeInterceptor;
import ai.myrmec.engine.websocket.host.HostControlWebSocketHandler;
import ai.myrmec.engine.websocket.host.HostProtocol;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * End-to-end execution lifecycle flow over the host control socket (Â§8):
 * execution.start (engineâ†’host), execution.accept, execution.delta fan-out,
 * execution.event ack, execution.complete with terminal dedup, and
 * execution.cancelled. Builds on {@link ai.myrmec.engine.inference.SessionAllocationFlowTest}.
 */
class ExecutionLifecycleFlowTest extends IntegrationTestBase {

    @Autowired HostControlWebSocketHandler handler;
    @Autowired SessionAllocator allocator;
    @Autowired SessionRepository sessionRepository;
    @Autowired SessionExecutionRepository executionRepository;
    @Autowired AgentHostInstanceRepository instances;
    @Autowired TestDataBuilder data;
    @Autowired ConversationService conversationService;
    @Autowired ConversationMessageRepository conversationMessageRepository;
    @Autowired ConversationStreamBroker conversationStreamBroker;
    @Autowired ExecutionCommandSender executionCommandSender;
    @Autowired ExecutionRegistry registry;
    @Autowired ExecutionInputAssembler executionInputAssembler;
    @Autowired ai.myrmec.engine.conversation.ConversationRepository conversationRepository;
    @Autowired ai.myrmec.engine.assistant.AssistantService assistantService;
    @Autowired ai.myrmec.engine.assistant.AssistantVersionService assistantVersionService;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private record Host(AgentHost host, AgentProfile profile, WebSocketSession session) {}

    private Host openedHost() throws Exception {
        AgentProfile profile = data.agentProfile().named("f-profile").create();
        AgentHostCreationResult created =
                data.agent().named("f-host").withMaxAgents(10).create();
        AgentHost host = created.agent();

        WebSocketSession session = mock(WebSocketSession.class);
        lenient().when(session.getId()).thenReturn("f-sock-" + UUID.randomUUID());
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
        return new Host(host, profile, session);
    }

    private List<JsonNode> replies(WebSocketSession session) {
        return org.mockito.Mockito.mockingDetails(session).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("sendMessage"))
                .map(i -> ((TextMessage) i.getArgument(0)).getPayload())
                .map(p -> { try { return mapper.readTree(p); } catch (Exception e) { throw new RuntimeException(e);} })
                .toList();
    }

    private JsonNode lastReply(WebSocketSession session) {
        return replies(session).get(replies(session).size() - 1);
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

    private record ActiveSession(UUID sessionId, UUID instanceId, Project project, Conversation conversation,
                                 UUID executionId, Host host) {}

    private ActiveSession openedConversationWithExecution() throws Exception {
        Host hostSetup = openedHost();
        UUID instanceId = (UUID) hostSetup.session().getAttributes()
                .get(HostControlWebSocketHandler.ATTR_HOST_INSTANCE_ID);

        // The agent host needs a project owner grant so the conversation service
        // can create the conversation and append the user turn under the
        // test admin identity.
        Project project = data.project().named("f-proj").create();

        // Create a real conversation with a USER message so the assembler returns messages.
        Conversation conversation = data.conversation().inProject(project).create();
        conversationService.appendMessage(conversation.getId(),
                ai.myrmec.engine.conversation.ConversationMessage.Role.USER,
                "Hello, engine", TEST_ADMIN_ID, null);
        // Pin an assistant profile version: the assembler resolves the profile
        // from the pin (Â§3.7), never from the host. Mirror the pinning-IT flow.
        ai.myrmec.engine.assistant.Assistant pinAssistant =
                assistantService.createAssistant(project.getId(),
                        "flow-assistant-" + System.nanoTime(), "flow",
                        hostSetup.profile().getId(), TEST_ADMIN_ID);
        ai.myrmec.engine.assistant.AssistantVersion pinnedVersion =
                assistantVersionService.publish(pinAssistant.getId(), TEST_ADMIN_ID);
        conversation.setAssistantId(pinAssistant.getId());
        conversation.setAssistantVersionId(pinnedVersion.getId());
        conversation.setAgentProfileVersionId(pinnedVersion.getAgentProfileVersionId());
        conversationRepository.save(conversation);
        // Bind the conversation to the host â€” the assembler's cold path resolves
        // the profile from conversation.agentId's AgentHost, exactly as the
        // dispatcher does (ExecutionInputAssembler.buildLegacySpec reads
        // agent.getProfileId()). NOTE: no manual AgentProfileVersion fixture is
        // needed â€” TestDataBuilder.agentProfile().create() publishes version 1
        // via AgentProfileService.createProfile() â†’ versionService.publishInitial(),
        // so findPublished() already resolves.
        conversation.setAgentId(hostSetup.host().getId());
        conversationRepository.save(conversation);

        UUID sessionId = allocator.offer("CONVERSATION", conversation.getId(), "CONVERSATION",
                project.getId(), hostSetup.host().getId()).orElseThrow();

        String accept = """
                { "protocolVersion": 1, "messageId": "m-acc", "type": "session.accept",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s",
                  "payload": { "allocationId": "%s", "sessionId": "%s", "acceptedAt": "%s" } }
                """.formatted(Instant.now(), instanceId, sessionId, sessionId, sessionId, Instant.now());
        ((WebSocketHandler) handler).handleMessage(hostSetup.session(), new TextMessage(accept));

        String openedFrame = """
                { "protocolVersion": 1, "messageId": "m-opd", "type": "session.opened",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s",
                  "payload": { "sessionId": "%s", "ready": true, "channelMode": "CONTROL" } }
                """.formatted(Instant.now(), instanceId, sessionId, sessionId);
        ((WebSocketHandler) handler).handleMessage(hostSetup.session(), new TextMessage(openedFrame));

        Session row = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(row.getAllocationState()).isEqualTo(SessionAllocator.ALLOC_STATE_ACTIVE);

        // Start a conversation execution through the registry.
        SessionExecution execution = registry.start(sessionId, conversation.getId().toString(),
                Instant.now().plusSeconds(60), Map.of()).orElseThrow();
        return new ActiveSession(sessionId, instanceId, project, conversation, execution.getId(), hostSetup);
    }

    @Test
    void startAcceptDeltaEventCompleteFlowWithAckDiscipline() throws Exception {
        ActiveSession setup = openedConversationWithExecution();

        // Subscribe a local sink to the conversation to verify delta fan-out.
        List<String> capturedDeltaFrames = new java.util.ArrayList<>();
        conversationStreamBroker.subscribe(setup.conversation().getId(), new ConversationSubscriber() {
            @Override public String id() { return "test-sub"; }
            @Override public boolean isOpen() { return true; }
            @Override public void send(String jsonFrame) throws IOException { capturedDeltaFrames.add(jsonFrame); }
        });

        // engineâ†’host execution.start frame (captured on the mock socket).
        boolean sent = executionCommandSender.startConversation(setup.executionId(),
                sessionRepository.findById(setup.sessionId()).orElseThrow(),
                buildStartPayload(setup.executionId(), setup.sessionId(), setup.conversation().getId()));
        assertThat(sent).isTrue();

        JsonNode startFrame = replies(setup.host().session()).stream()
                .filter(n -> "execution.start".equals(n.path("type").asText()))
                .findFirst().orElseThrow();
        assertThat(startFrame.path("executionId").asText()).isEqualTo(setup.executionId().toString());
        assertThat(startFrame.path("sessionId").asText()).isEqualTo(setup.sessionId().toString());
        assertThat(startFrame.path("payload").path("input").path("messages").isArray()).isTrue();
        assertThat(startFrame.path("payload").path("input").path("messages").size()).isGreaterThanOrEqualTo(1);

        // execution.accept
        String accept = """
                { "protocolVersion": 1, "messageId": "m-accept", "type": "execution.accept",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s", "executionId": "%s",
                  "payload": { "executionId": "%s", "startedAt": "%s", "resolvedModelId": "m1",
                  "dispatchId": "%s", "assignmentDigest": "abc" } }
                """.formatted(Instant.now(), setup.instanceId(), setup.sessionId(), setup.executionId(),
                setup.executionId(), Instant.now(), UUID.randomUUID());
        ((WebSocketHandler) handler).handleMessage(setup.host().session(), new TextMessage(accept));

        SessionExecution running = executionRepository.findById(setup.executionId()).orElseThrow();
        assertThat(running.getState()).isEqualTo(SessionExecution.State.RUNNING);

        // execution.delta x2 â€” ephemeral, no ack.
        for (int i = 0; i < 2; i++) {
            String delta = """
                    { "protocolVersion": 1, "messageId": "m-delta-%d", "type": "execution.delta",
                      "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s", "executionId": "%s",
                      "payload": { "executionId": "%s", "index": %d, "content": "tok%d", "contentType": "text" } }
                    """.formatted(i, Instant.now(), setup.instanceId(), setup.sessionId(), setup.executionId(),
                    setup.executionId(), i, i);
            ((WebSocketHandler) handler).handleMessage(setup.host().session(), new TextMessage(delta));
        }
        assertThat(capturedDeltaFrames).hasSize(2);
        JsonNode firstDelta = mapper.readTree(capturedDeltaFrames.get(0));
        assertThat(firstDelta.path("type").asText()).isEqualTo("message.delta");
        assertThat(firstDelta.path("payload").path("conversationId").asText())
                .isEqualTo(setup.conversation().getId().toString());
        assertThat(firstDelta.path("payload").path("deltaIndex").asInt()).isEqualTo(0);

        // execution.event with sequence=1
        UUID eventId = UUID.randomUUID();
        String event = """
                { "protocolVersion": 1, "messageId": "m-event", "type": "execution.event",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s", "executionId": "%s",
                  "sequence": 1,
                  "payload": { "executionId": "%s", "eventId": "%s", "eventType": "PROGRESS",
                  "occurredAt": "%s", "data": { "pct": 50 } } }
                """.formatted(Instant.now(), setup.instanceId(), setup.sessionId(), setup.executionId(),
                setup.executionId(), eventId, Instant.now());
        ((WebSocketHandler) handler).handleMessage(setup.host().session(), new TextMessage(event));

        JsonNode ack = ackForMessageId(setup.host().session(), "m-event");
        assertThat(ack).isNotNull();
        assertThat(ack.path("type").asText()).isEqualTo("protocol.ack");
        assertThat(ack.path("payload").path("acknowledgedMessageId").asText()).isEqualTo("m-event");
        assertThat(ack.path("payload").path("highestContiguousSequence").asLong()).isEqualTo(1L);
        assertThat(ack.path("payload").path("status").asText()).isEqualTo("DURABLY_RECORDED");
        assertThat(sessionRepository.findById(setup.sessionId()).orElseThrow().getHighestContiguousSequence())
                .isEqualTo(1L);

        // execution.complete
        String complete = """
                { "protocolVersion": 1, "messageId": "m-term", "type": "execution.complete",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s", "executionId": "%s",
                  "payload": { "executionId": "%s", "completedAt": "%s",
                  "result": { "content": "done", "structured": null, "artifacts": [] },
                  "usage": { "modelId": "m1", "inputTokens": 3, "outputTokens": 5, "durationMs": 100 } } }
                """.formatted(Instant.now(), setup.instanceId(), setup.sessionId(), setup.executionId(),
                setup.executionId(), Instant.now());
        ((WebSocketHandler) handler).handleMessage(setup.host().session(), new TextMessage(complete));

        SessionExecution completed = executionRepository.findById(setup.executionId()).orElseThrow();
        assertThat(completed.getState()).isEqualTo(SessionExecution.State.COMPLETED);
        assertThat(completed.getTerminalMessageId()).isEqualTo("m-term");
        assertThat(completed.getTerminalPayload().get("result")).isNotNull();
        assertThat(ackForMessageId(setup.host().session(), "m-term")).isNotNull();
        assertThat(conversationMessageRepository.findByConversationIdOrderBySequenceNoAsc(setup.conversation().getId()))
                .anyMatch(m -> m.getRole() == ai.myrmec.engine.conversation.ConversationMessage.Role.ASSISTANT
                        && "done".equals(m.getContent()));

        // REPLAY: same messageId "m-term" â€” row unchanged, second ack.
        String replay = """
                { "protocolVersion": 1, "messageId": "m-term", "type": "execution.complete",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s", "executionId": "%s",
                  "payload": { "executionId": "%s", "completedAt": "%s",
                  "result": { "content": "CHANGED", "structured": null, "artifacts": [] },
                  "usage": { "modelId": "m2", "inputTokens": 99, "outputTokens": 99, "durationMs": 999 } } }
                """.formatted(Instant.now(), setup.instanceId(), setup.sessionId(), setup.executionId(),
                setup.executionId(), Instant.now());
        ((WebSocketHandler) handler).handleMessage(setup.host().session(), new TextMessage(replay));

        SessionExecution still = executionRepository.findById(setup.executionId()).orElseThrow();
        assertThat(still.getTerminalMessageId()).isEqualTo("m-term");
        assertThat(still.getTerminalPayload().get("result")).isNotNull();
        assertThat(((Map<?, ?>) still.getTerminalPayload().get("result")).get("content")).isEqualTo("done");
        assertThat(still.getState()).isEqualTo(SessionExecution.State.COMPLETED);
        // No duplicate assistant row from replay.
        assertThat(conversationMessageRepository.findByConversationIdOrderBySequenceNoAsc(setup.conversation().getId())
                .stream().filter(m -> m.getRole() == ai.myrmec.engine.conversation.ConversationMessage.Role.ASSISTANT
                        && "done".equals(m.getContent())).count()).isEqualTo(1L);

        // Conflicting terminal messageId â€” INVALID_STATE error.
        String conflicting = """
                { "protocolVersion": 1, "messageId": "m-term2", "type": "execution.complete",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s", "executionId": "%s",
                  "payload": { "executionId": "%s", "completedAt": "%s",
                  "result": { "content": "also done", "structured": null, "artifacts": [] },
                  "usage": { "modelId": "m1", "inputTokens": 1, "outputTokens": 1, "durationMs": 1 } } }
                """.formatted(Instant.now(), setup.instanceId(), setup.sessionId(), setup.executionId(),
                setup.executionId(), Instant.now());
        ((WebSocketHandler) handler).handleMessage(setup.host().session(), new TextMessage(conflicting));
        JsonNode error = lastReply(setup.host().session());
        assertThat(error.path("type").asText()).isEqualTo("protocol.error");
        assertThat(error.path("payload").path("code").asText()).isEqualTo("INVALID_STATE");
    }

    @Test
    void outOfOrderAndBogusFramesStayDisciplined() throws Exception {
        Host hostSetup = openedHost();
        UUID instanceId = (UUID) hostSetup.session().getAttributes()
                .get(HostControlWebSocketHandler.ATTR_HOST_INSTANCE_ID);
        Project project = data.project().named("f-proj2").create();
        UUID executionId = UUID.randomUUID();

        // execution.complete before start â€” no execution row.
        String complete = """
                { "protocolVersion": 1, "messageId": "m-term", "type": "execution.complete",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s", "executionId": "%s",
                  "payload": { "executionId": "%s", "completedAt": "%s",
                  "result": { "content": "done" }, "usage": {} } }
                """.formatted(Instant.now(), instanceId, UUID.randomUUID(), executionId, executionId, Instant.now());
        ((WebSocketHandler) handler).handleMessage(hostSetup.session(), new TextMessage(complete));
        JsonNode error = lastReply(hostSetup.session());
        assertThat(error.path("type").asText()).isEqualTo("protocol.error");
        assertThat(error.path("payload").path("code").asText()).isEqualTo("INVALID_STATE");

        // execution.accept with no executionId â€” INVALID_MESSAGE
        String acceptNoId = """
                { "protocolVersion": 1, "messageId": "m-accept-bad", "type": "execution.accept",
                  "sentAt": "%s", "hostInstanceId": "%s",
                  "payload": { "startedAt": "%s", "resolvedModelId": "m1" } }
                """.formatted(Instant.now(), instanceId, Instant.now());
        ((WebSocketHandler) handler).handleMessage(hostSetup.session(), new TextMessage(acceptNoId));
        JsonNode err2 = lastReply(hostSetup.session());
        assertThat(err2.path("type").asText()).isEqualTo("protocol.error");
        assertThat(err2.path("payload").path("code").asText()).isEqualTo("INVALID_MESSAGE");

        // execution.start echoed from host â€” ignored. Snapshot the reply count
        // first and assert the echo produced NO new protocol.error (count-based,
        // not lastReply â€” the earlier discipline checks left errors in history).
        int errorCountBefore = (int) replies(hostSetup.session()).stream()
                .filter(n -> "protocol.error".equals(n.path("type").asText())).count();
        String echoedStart = """
                { "protocolVersion": 1, "messageId": "m-start-echo", "type": "execution.start",
                  "sentAt": "%s", "hostInstanceId": "%s",
                  "payload": { "executionId": "%s" } }
                """.formatted(Instant.now(), instanceId, executionId);
        ((WebSocketHandler) handler).handleMessage(hostSetup.session(), new TextMessage(echoedStart));
        assertThat(replies(hostSetup.session()).stream()
                .filter(n -> "protocol.error".equals(n.path("type").asText())).count())
                .isEqualTo(errorCountBefore);

        // execution.delta without executionId â€” dropped silently (also count-based).
        int errorCountBeforeDelta = (int) replies(hostSetup.session()).stream()
                .filter(n -> "protocol.error".equals(n.path("type").asText())).count();
        String deltaNoId = """
                { "protocolVersion": 1, "messageId": "m-delta-bad", "type": "execution.delta",
                  "sentAt": "%s", "hostInstanceId": "%s",
                  "payload": { "index": 0, "content": "x" } }
                """.formatted(Instant.now(), instanceId);
        ((WebSocketHandler) handler).handleMessage(hostSetup.session(), new TextMessage(deltaNoId));
        assertThat(replies(hostSetup.session()).stream()
                .filter(n -> "protocol.error".equals(n.path("type").asText())).count())
                .isEqualTo(errorCountBeforeDelta);
    }

    @Test
    void cancelFlowMarksCancellingAndTerminalCancelled() throws Exception {
        ActiveSession setup = openedConversationWithExecution();

        String accept = """
                { "protocolVersion": 1, "messageId": "m-accept", "type": "execution.accept",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s", "executionId": "%s",
                  "payload": { "executionId": "%s", "startedAt": "%s", "resolvedModelId": "m1",
                  "dispatchId": "%s", "assignmentDigest": "abc" } }
                """.formatted(Instant.now(), setup.instanceId(), setup.sessionId(), setup.executionId(),
                setup.executionId(), Instant.now(), UUID.randomUUID());
        ((WebSocketHandler) handler).handleMessage(setup.host().session(), new TextMessage(accept));

        // execution.cancel is engineâ†’host; echoed copies are ignored.
        String echoedCancel = """
                { "protocolVersion": 1, "messageId": "m-cancel-echo", "type": "execution.cancel",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s", "executionId": "%s",
                  "payload": { "executionId": "%s", "dispatchId": "%s", "reasonCode": "USER_CANCEL",
                  "cancelledAt": "%s", "gracePeriodSeconds": 5 } }
                """.formatted(Instant.now(), setup.instanceId(), setup.sessionId(), setup.executionId(),
                setup.executionId(), UUID.randomUUID(), Instant.now());
        ((WebSocketHandler) handler).handleMessage(setup.host().session(), new TextMessage(echoedCancel));
        SessionExecution stillRunning = executionRepository.findById(setup.executionId()).orElseThrow();
        assertThat(stillRunning.getState()).isEqualTo(SessionExecution.State.RUNNING);

        // Engine sends execution.cancel directly.
        boolean sent = executionCommandSender.cancel(setup.executionId(),
                sessionRepository.findById(setup.sessionId()).orElseThrow(), UUID.randomUUID(),
                "USER_CANCEL", 5);
        assertThat(sent).isTrue();
        assertThat(replies(setup.host().session()).stream()
                .anyMatch(n -> "execution.cancel".equals(n.path("type").asText()))).isTrue();

        // Host answers execution.cancelled.
        String cancelled = """
                { "protocolVersion": 1, "messageId": "m-cancelled", "type": "execution.cancelled",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s", "executionId": "%s",
                  "payload": { "executionId": "%s", "dispatchId": "%s", "cancelledAt": "%s",
                  "reasonCode": "USER_CANCEL" } }
                """.formatted(Instant.now(), setup.instanceId(), setup.sessionId(), setup.executionId(),
                setup.executionId(), UUID.randomUUID(), Instant.now());
        ((WebSocketHandler) handler).handleMessage(setup.host().session(), new TextMessage(cancelled));

        SessionExecution terminal = executionRepository.findById(setup.executionId()).orElseThrow();
        assertThat(terminal.getState()).isEqualTo(SessionExecution.State.CANCELLED);
        assertThat(terminal.getTerminalMessageId()).isEqualTo("m-cancelled");
        assertThat(ackForMessageId(setup.host().session(), "m-cancelled")).isNotNull();
    }

    private ai.myrmec.engine.websocket.host.payload.ExecutionStartPayload buildStartPayload(
            UUID executionId, UUID sessionId, UUID conversationId) {
        Session session = sessionRepository.findById(sessionId).orElseThrow();
        long sequenceNo = executionRepository.findById(executionId)
                .map(SessionExecution::getSequenceNo).map(Integer::longValue).orElse(1L);
        Map<String, Object> input = executionInputAssembler.assembleConversationInput(
                conversationId, sessionId, session.getProjectId(), sequenceNo);

        // assembleConversationInput's "messages" IS the legacy transcript â€” a
        // List<InferenceMessage> (the assembler returns assign.messages()
        // unchanged). Build the wire payload from it directly.
        @SuppressWarnings("unchecked")
        List<ai.myrmec.engine.inference.InferenceMessage> wireMessages =
                (List<ai.myrmec.engine.inference.InferenceMessage>) input.get("messages");
        var toolPolicy = (Map<String, Object>) input.get("toolPolicy");
        @SuppressWarnings("unchecked")
        List<String> activeTools = toolPolicy.get("activeToolNames") instanceof List
                ? (List<String>) toolPolicy.get("activeToolNames") : List.of();

        return new ai.myrmec.engine.websocket.host.payload.ExecutionStartPayload(
                executionId, sessionId, (int) sequenceNo, conversationId.toString(),
                Instant.now().plusSeconds(60),
                new ai.myrmec.engine.websocket.host.payload.ExecutionStartPayload.Input(
                        wireMessages, null, null),
                new ai.myrmec.engine.websocket.host.payload.ExecutionStartPayload.ToolPolicy(
                        activeTools, "ENGINE"),
                new ai.myrmec.engine.websocket.host.payload.ExecutionStartPayload.Output(
                        true, (int) sequenceNo, "TEXT"));
    }
}
