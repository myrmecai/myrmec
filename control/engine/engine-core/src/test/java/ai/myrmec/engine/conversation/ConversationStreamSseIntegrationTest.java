package ai.myrmec.engine.conversation;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.conversation.stream.ConversationStreamBroker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * User-facing live conversation stream end-to-end test (SSE transport).
 *
 * <p>Boots the engine on a random port, opens a real
 * {@code text/event-stream} connection against
 * {@code /api/v1/conversations/{id}/stream}, then verifies:</p>
 * <ol>
 *   <li>History replay frames arrive on connect.</li>
 *   <li>Frames published to the broker (which the agent handler would
 *       feed in production) reach the subscriber.</li>
 *   <li>An unauthorised connect (no token) is rejected with 401.</li>
 * </ol>
 *
 * <p>The broker is hit directly here rather than driving a real agent
 * WS — keeps the test focused on the user side.</p>
 */
class ConversationStreamSseIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ConversationStreamBroker broker;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private String baseUrl() {
        return restTemplate.getRootUri();
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
        var project = projectFor("user-sse-history");
        Conversation conv = conversationService.createConversation(
                project, TEST_ADMIN_ID, "history test");
        ConversationMessage seeded = conversationService.appendMessage(
                conv.getId(), ConversationMessage.Role.USER,
                "seed question", TEST_ADMIN_ID, null);

        BlockingQueue<String> dataFrames = new LinkedBlockingQueue<>();
        URI uri = URI.create(baseUrl() + "/api/v1/conversations/" + conv.getId()
                + "/stream?token=" + adminToken());
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Accept", "text/event-stream")
                .GET()
                .build();

        HttpResponse<Stream<String>> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofLines());
        assertThat(response.statusCode()).isEqualTo(200);

        Thread reader = new Thread(() -> response.body().forEach(line -> {
            // SSE data lines look like "data:{json}"; collect those that
            // parse to a JSON object (skips heartbeat timestamps + comments).
            if (line.startsWith("data:")) {
                String payload = line.substring("data:".length()).trim();
                if (payload.startsWith("{")) {
                    dataFrames.add(payload);
                }
            }
        }));
        reader.setDaemon(true);
        reader.start();

        try {
            // 1. History replay — one row was seeded.
            String history = dataFrames.poll(3, TimeUnit.SECONDS);
            assertThat(history).isNotNull();
            JsonNode historyNode = objectMapper.readTree(history);
            assertThat(historyNode.path("type").asText()).isEqualTo("history.message");
            assertThat(historyNode.path("payload").path("messageId").asText())
                    .isEqualTo(seeded.getId().toString());
            assertThat(historyNode.path("payload").path("content").asText())
                    .isEqualTo("seed question");
            assertThat(historyNode.path("payload").path("role").asText()).isEqualTo("USER");

            // 2. Live frame produced via the broker — simulates a streamed
            // agent delta. Wait until our SSE subscriber is registered.
            for (int i = 0; i < 50 && broker.subscriberCount(conv.getId()) < 1; i++) {
                Thread.sleep(20);
            }
            String simulated = "{\"type\":\"message.delta\",\"payload\":{"
                    + "\"conversationId\":\"" + conv.getId() + "\","
                    + "\"sequenceNo\":7,\"deltaIndex\":0,\"content\":\"hello\"}}";
            int delivered = broker.broadcast(conv.getId(), simulated);
            assertThat(delivered).isGreaterThanOrEqualTo(1);

            String live = dataFrames.poll(3, TimeUnit.SECONDS);
            assertThat(live).isNotNull();
            assertThat(objectMapper.readTree(live).path("type").asText())
                    .isEqualTo("message.delta");
            assertThat(objectMapper.readTree(live).path("payload").path("content").asText())
                    .isEqualTo("hello");
        } finally {
            reader.interrupt();
        }
    }

    @Test
    void connectRejectedWithoutToken() throws Exception {
        var project = projectFor("user-sse-noauth");
        Conversation conv = conversationService.createConversation(
                project, TEST_ADMIN_ID, "no token");

        URI uri = URI.create(baseUrl() + "/api/v1/conversations/" + conv.getId() + "/stream");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Accept", "text/event-stream")
                .GET()
                .build();

        HttpResponse<Void> response = httpClient.send(
                request, HttpResponse.BodyHandlers.discarding());
        // The app configures no custom AuthenticationEntryPoint, so Spring
        // Security's default normalises every unauthenticated API request to
        // 403 (the controller's 401 is re-dispatched through /error while the
        // caller is anonymous). Either way the stream is refused.
        assertThat(response.statusCode()).isEqualTo(403);
    }

    private UUID projectFor(String name) {
        return data.project().named(name).create().getId();
    }

    @Autowired
    private ai.myrmec.engine.testing.TestDataBuilder data;
}
