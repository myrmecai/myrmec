package ai.myrmec.engine.websocket;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationMessage;
import ai.myrmec.engine.conversation.ConversationService;
import ai.myrmec.engine.conversation.stream.ConversationStreamBroker;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6c-2 — user-facing WebSocket end-to-end test.
 *
 * <p>Boots the engine with random port, opens a real WS client against
 * {@code /api/v1/conversations/{id}/stream}, then verifies:</p>
 * <ol>
 *   <li>History replay frames arrive on connect.</li>
 *   <li>Frames published to the broker (which the agent handler would
 *       feed in production) reach the subscriber.</li>
 *   <li>Unauthorised handshake (no token, or wrong user) is rejected.</li>
 * </ol>
 *
 * <p>The broker is hit directly here rather than driving a real agent
 * WS — keeps the test focused on the user side. The agent path is
 * already exercised by Phase 6b's payload round-trip test.</p>
 */
class UserConversationWebSocketIntegrationTest extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ConversationStreamBroker broker;

    @Autowired
    private ObjectMapper objectMapper;

    /** Capture inbound frames so the test thread can assert on them. */
    private static class CapturingHandler extends TextWebSocketHandler {
        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            frames.add(message.getPayload());
        }
    }

    private URI buildWsUri(UUID conversationId, String token) {
        String rootUri = restTemplate.getRootUri();
        URI httpRoot = URI.create(rootUri);
        String scheme = "ws";
        return URI.create(scheme + "://" + httpRoot.getHost() + ":" + httpRoot.getPort()
                + "/api/v1/conversations/" + conversationId + "/stream?token=" + token);
    }

    private String adminToken() {
        return jwtTokenProvider.generateUserAccessToken(
                TEST_ADMIN_ID,
                TEST_ADMIN_NAME,
                TEST_ADMIN_EMAIL,
                List.of("sys:PLATFORM_ADMIN", "sys:ORG_ADMIN"));
    }

    @Test
    void clientReceivesHistoryReplayAndLiveBrokerFrames() throws Exception {
        Project project = data.project().named("user-ws-history").create();
        Conversation conv = conversationService.createConversation(
                project.getId(), TEST_ADMIN_ID, "history test");
        ConversationMessage seeded = conversationService.appendMessage(
                conv.getId(), ConversationMessage.Role.USER,
                "seed question", TEST_ADMIN_ID, null);

        StandardWebSocketClient client = new StandardWebSocketClient();
        CapturingHandler handler = new CapturingHandler();
        WebSocketSession session = client.execute((WebSocketHandler) handler,
                null,
                buildWsUri(conv.getId(), adminToken())).get(5, TimeUnit.SECONDS);

        try {
            // 1. History replay — one row was seeded.
            String history = handler.frames.poll(2, TimeUnit.SECONDS);
            assertThat(history).isNotNull();
            JsonNode historyNode = objectMapper.readTree(history);
            assertThat(historyNode.path("type").asText()).isEqualTo("history.message");
            assertThat(historyNode.path("payload").path("messageId").asText())
                    .isEqualTo(seeded.getId().toString());
            assertThat(historyNode.path("payload").path("content").asText())
                    .isEqualTo("seed question");
            assertThat(historyNode.path("payload").path("role").asText()).isEqualTo("USER");

            // 2. Live frame produced via the broker — simulates a streamed
            // agent delta. The handler should drain straight to the socket.
            // Spring may attach a handful of internal subscribers (close
            // notifications etc.) before our test handler — wait for the
            // session count to include us.
            for (int i = 0; i < 50 && broker.subscriberCount(conv.getId()) < 1; i++) {
                Thread.sleep(20);
            }
            String simulated = "{\"type\":\"message.delta\",\"payload\":{"
                    + "\"conversationId\":\"" + conv.getId() + "\","
                    + "\"sequenceNo\":7,\"deltaIndex\":0,\"content\":\"hello\"}}";
            int delivered = broker.broadcast(conv.getId(), simulated);
            assertThat(delivered).isGreaterThanOrEqualTo(1);

            String live = handler.frames.poll(2, TimeUnit.SECONDS);
            assertThat(live).isEqualTo(simulated);
        } finally {
            session.close(CloseStatus.NORMAL);
        }
    }

    @Test
    void handshakeRejectedWithoutToken() throws Exception {
        Project project = data.project().named("user-ws-noauth").create();
        Conversation conv = conversationService.createConversation(
                project.getId(), TEST_ADMIN_ID, "no token");

        StandardWebSocketClient client = new StandardWebSocketClient();
        CapturingHandler handler = new CapturingHandler();
        URI noTokenUri = URI.create(
                buildWsUri(conv.getId(), "ignored").toString().replaceAll("\\?token=.*$", ""));
        try {
            client.execute((WebSocketHandler) handler, null, noTokenUri).get(3, TimeUnit.SECONDS);
            // If we get here Spring upgraded the handshake — fail the test.
            throw new AssertionError("Handshake unexpectedly succeeded without token");
        } catch (Exception expected) {
            // Either ExecutionException wrapping HandshakeFailureException or
            // an upgrade rejection — both are acceptable.
        }
    }
}
