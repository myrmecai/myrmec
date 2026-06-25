package ai.myrmec.engine.conversation;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentCreationResult;
import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentRepository;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.agent.AgentService;
import ai.myrmec.engine.conversation.dto.ApprovalDecisionRequest;
import ai.myrmec.engine.node.EngineNode;
import ai.myrmec.engine.node.EngineNodeRepository;
import ai.myrmec.engine.node.NodeRegistryService;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.websocket.AgentConversationWebSocketHandler;
import ai.myrmec.engine.websocket.ConversationSocketRegistry;
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
 * Slice 4c-2b end-to-end: the full HITL approval loop rides the
 * conversation socket. A bound worker sends an approval.request frame
 * over its conversation socket, the engine persists + broadcasts it, an
 * admin POSTs a decision via REST, and the engine pushes the
 * approval.decision frame back over the same conversation socket.
 *
 * <p>The whole loop runs without a real agent process: a Mockito stub
 * conversation session stands in for the worker, identical to the
 * pattern in
 * {@link ai.myrmec.engine.conversation.dispatch.ConversationTurnLoopE2ETest}.</p>
 */
class HitlApprovalLoopE2ETest extends IntegrationTestBase {

    @Autowired private TestDataBuilder data;
    @Autowired private ConversationService conversationService;
    @Autowired private AgentRepository agentInstanceRepository;
    @Autowired private AgentService agentService;
    @Autowired private AgentConversationWebSocketHandler conversationHandler;
    @Autowired private ConversationSocketRegistry conversationSocketRegistry;
    @Autowired private NodeRegistryService nodeRegistry;
    @Autowired private EngineNodeRepository engineNodeRepository;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void agentRequestsApprovalAdminDecidesAndAgentReceivesDecisionFrame() throws Exception {
        // ---------- Arrange ----------
        ensureSelfNodeRegistered();
        Project project = data.project().named("hitl-loop").create();
        AgentProfile profile = data.agentProfile()
                .named("hitl-loop-profile")
                .withSystemPrompt("test")
                .create();
        AgentCreationResult created = data.agent()
                .named("hitl-loop-agent")
                .withProfile(profile)
                .inProject(project)
                .create();
        AgentHost agent = created.agent();

        Agent instance = new Agent();
        instance.setAgentHostId(agent.getId());
        instance.setHostname("hitl-loop");
        instance.setRuntimeVersion("0.0.0");
        instance.setStatus(Agent.Status.IDLE);
        instance.setRegisteredAt(Instant.now());
        instance = agentInstanceRepository.save(instance);

        Conversation conv = conversationService.createConversation(
                project.getId(), TEST_ADMIN_ID,
                "hitl-loop-conv", agent.getId(), null);
        conversationService.addParticipant(
                conv.getId(), TEST_ADMIN_ID,
                ConversationParticipant.Role.OWNER);

        // Reserve the worker for this conversation, then attach its
        // conversation socket so it flips RESERVED -> BOUND and the socket
        // is registered for both inbound approval.request and outbound
        // approval.decision.
        boolean reserved = agentService.reserveInstance(instance.getId(), conv.getId(), null);
        assertThat(reserved).as("worker must reserve for the conversation").isTrue();

        BlockingQueue<String> convOutbound = new LinkedBlockingQueue<>();
        WebSocketSession convSession = buildStubSession(
                "hitl-conv-stub", instance.getId(), "hitl-loop-agent", convOutbound);
        String attachFrame = "{\"type\":\"conversation.attach\",\"payload\":{"
                + "\"agentId\":\"" + instance.getId() + "\","
                + "\"conversationId\":\"" + conv.getId() + "\"}}";
        ((WebSocketHandler) conversationHandler)
                .handleMessage(convSession, new TextMessage(attachFrame));
        assertThat(agentInstanceRepository.findById(instance.getId()).orElseThrow().getStatus())
                .as("attach flips the reserved worker to BOUND")
                .isEqualTo(Agent.Status.BOUND);

        // ---------- Act 1 -- agent sends approval.request over conv socket ----------
        UUID clientRequestId = UUID.randomUUID();
        String requestFrame = "{\"type\":\"approval.request\",\"payload\":{"
                + "\"conversationId\":\"" + conv.getId() + "\","
                + "\"clientRequestId\":\"" + clientRequestId + "\","
                + "\"content\":\"DROP TABLE users\","
                + "\"payloadJson\":\"{\\\"clientRequestId\\\":\\\"" + clientRequestId
                + "\\\",\\\"sql\\\":\\\"DROP TABLE users\\\"}\""
                + "}}";
        ((WebSocketHandler) conversationHandler)
                .handleMessage(convSession, new TextMessage(requestFrame));

        // ---------- Assert 1 -- APPROVAL_REQUEST row persisted ----------
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

        // ---------- Act 2 -- admin POSTs the approval decision ----------
        ApprovalDecisionRequest body = new ApprovalDecisionRequest(
                ConversationMessage.ApprovalStatus.APPROVED, "OK, proceed");
        ResponseEntity<String> decisionResp = restTemplate.exchange(
                "/api/v1/conversations/" + conv.getId()
                        + "/approvals/" + request.getId(),
                HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders()),
                String.class);
        assertThat(decisionResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // ---------- Assert 2 -- agent received approval.decision over conv socket ----------
        String decisionFrame = convOutbound.poll(3, TimeUnit.SECONDS);
        assertThat(decisionFrame)
                .as("agent must receive an approval.decision frame on its conversation socket")
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
        conversationSocketRegistry.unregisterBySession(convSession);
    }

    /**
     * The e2e profile disables NodeRegistryService, so the self
     * engine_nodes row the home_node_id FK references is never written on
     * startup. Seed it idempotently before any attach pins a home node.
     */
    private void ensureSelfNodeRegistered() {
        String selfId = nodeRegistry.getSelfNodeId();
        if (engineNodeRepository.findById(selfId).isEmpty()) {
            EngineNode node = new EngineNode();
            node.setNodeId(selfId);
            node.setAddress(nodeRegistry.getSelfAddress());
            node.setStatus(EngineNode.Status.UP);
            node.setStartedAt(Instant.now());
            node.setLastHeartbeatAt(Instant.now());
            engineNodeRepository.save(node);
        }
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
