package ai.myrmec.engine.conversation;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentInstance;
import ai.myrmec.engine.agent.AgentInstanceRepository;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.conversation.dto.ApprovalDecisionRequest;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.websocket.AgentConnectionManager;
import ai.myrmec.engine.websocket.AgentWebSocketHandler;
import ai.myrmec.engine.websocket.message.MessageType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Phase 7c — end-to-end: agent sends an {@code approval.request} frame,
 * the engine persists + broadcasts it, the admin POSTs a decision via
 * REST, and the engine pushes an {@code approval.decision} frame back
 * to the originating agent's WebSocket session.
 *
 * <p>The whole loop runs without a real agent process — Mockito stub
 * sessions for the agent + viewer, identical pattern to
 * {@link ai.myrmec.engine.conversation.dispatch.ConversationTurnLoopE2ETest}.</p>
 */
class HitlApprovalLoopE2ETest extends IntegrationTestBase {

    @Autowired private TestDataBuilder data;
    @Autowired private ConversationService conversationService;
    @Autowired private AgentInstanceRepository agentInstanceRepository;
    @Autowired private AgentConnectionManager connectionManager;
    @Autowired private AgentWebSocketHandler webSocketHandler;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void agentRequestsApprovalAdminDecidesAndAgentReceivesDecisionFrame() throws Exception {
        // ---------- Arrange ----------
        var project = data.project().named("hitl-loop").create();
        AgentProfile profile = data.agentProfile()
                .named("hitl-loop-profile")
                .withSystemPrompt("test")
                .create();
        Agent agent = data.agent()
                .named("hitl-loop-agent")
                .withProfile(profile)
                .inProject(project)
                .create()
                .agent();

        AgentInstance instance = new AgentInstance();
        instance.setAgentId(agent.getId());
        instance.setHostname("hitl-loop");
        instance.setRuntimeVersion("0.0.0");
        instance.setStatus(AgentInstance.Status.ONLINE);
        instance.setRegisteredAt(Instant.now());
        instance = agentInstanceRepository.save(instance);

        BlockingQueue<String> agentOutbound = new LinkedBlockingQueue<>();
        WebSocketSession agentSession = buildStubSession(
                "hitl-agent-stub", instance.getId(), "hitl-loop-agent", agentOutbound);
        connectionManager.register(instance.getId(), "hitl-loop-agent", agentSession);

        var conv = conversationService.createConversation(
                project.getId(), TEST_ADMIN_ID,
                "hitl-loop-conv", agent.getId(), null);
        conversationService.addParticipant(
                conv.getId(), TEST_ADMIN_ID,
                ConversationParticipant.Role.OWNER);

        // ---------- Act 1 — agent sends approval.request via WS ----------
        UUID clientRequestId = UUID.randomUUID();
        String requestFrame = "{\"type\":\"approval.request\",\"payload\":{"
                + "\"conversationId\":\"" + conv.getId() + "\","
                + "\"clientRequestId\":\"" + clientRequestId + "\","
                + "\"content\":\"DROP TABLE users\","
                + "\"payloadJson\":\"{\\\"clientRequestId\\\":\\\"" + clientRequestId
                + "\\\",\\\"sql\\\":\\\"DROP TABLE users\\\"}\""
                + "}}";

        WebSocketHandler asHandler = webSocketHandler;
        asHandler.handleMessage(agentSession, new TextMessage(requestFrame));

        // ---------- Assert 1 — APPROVAL_REQUEST row persisted ----------
        List<ConversationMessage> after = conversationService.listMessages(conv.getId());
        assertThat(after)
                .as("approval request must persist as APPROVAL_REQUEST row")
                .extracting(ConversationMessage::getRole)
                .contains(ConversationMessage.Role.APPROVAL_REQUEST);
        ConversationMessage request = after.stream()
                .filter(m -> m.getRole() == ConversationMessage.Role.APPROVAL_REQUEST)
                .findFirst().orElseThrow();
        assertThat(request.getApprovalStatus())
                .isEqualTo(ConversationMessage.ApprovalStatus.PENDING);
        assertThat(request.getPayloadJson())
                .as("payload JSON round-tripped intact for dispatcher to recover clientRequestId")
                .contains(clientRequestId.toString());

        // ---------- Act 2 — admin POSTs the approval decision ----------
        ApprovalDecisionRequest body = new ApprovalDecisionRequest(
                ConversationMessage.ApprovalStatus.APPROVED, "OK, proceed");
        ResponseEntity<String> decisionResp = restTemplate.exchange(
                "/api/v1/conversations/" + conv.getId()
                        + "/approvals/" + request.getId(),
                HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders()),
                String.class);
        assertThat(decisionResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // ---------- Assert 2 — agent received approval.decision frame ----------
        String decisionFrame = agentOutbound.poll(3, TimeUnit.SECONDS);
        assertThat(decisionFrame)
                .as("agent must receive an approval.decision frame")
                .isNotNull();
        JsonNode envelope = objectMapper.readTree(decisionFrame);
        assertThat(envelope.path("type").asText()).isEqualTo(MessageType.APPROVAL_DECISION);
        JsonNode payload = envelope.path("payload");
        assertThat(payload.path("conversationId").asText()).isEqualTo(conv.getId().toString());
        assertThat(payload.path("requestMessageId").asText()).isEqualTo(request.getId().toString());
        assertThat(payload.path("clientRequestId").asText())
                .as("dispatcher must recover the agent's clientRequestId from payloadJson")
                .isEqualTo(clientRequestId.toString());
        assertThat(payload.path("decision").asText()).isEqualTo("APPROVED");
        assertThat(payload.path("comment").asText()).isEqualTo("OK, proceed");
        assertThat(payload.path("approverUserId").asText()).isEqualTo(TEST_ADMIN_ID.toString());

        // ---------- Cleanup ----------
        connectionManager.unregister(agentSession);
    }

    private WebSocketSession buildStubSession(
            String sessionId,
            UUID agentInstanceId,
            String agentName,
            BlockingQueue<String> outbound) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        if (agentInstanceId != null) {
            attrs.put("agentInstanceId", agentInstanceId);
            attrs.put("agentName", agentName);
        }
        lenient().when(session.getId()).thenReturn(sessionId);
        lenient().when(session.isOpen()).thenReturn(true);
        lenient().when(session.getAttributes()).thenReturn(attrs);
        doAnswer(inv -> {
            TextMessage msg = inv.getArgument(0);
            outbound.add(msg.getPayload());
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        return session;
    }
}
