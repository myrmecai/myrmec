package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.spi.connector.ConnectorException;
import ai.myrmec.engine.spi.connector.SyncResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@link NotionConnector} (#24). Stands up a loopback
 * {@link HttpServer} that mimics the Notion REST API (bearer auth, cursor
 * pagination on {@code /v1/search}, and recursive block children on
 * {@code /v1/blocks/{id}/children}), then drives a sync through {@link
 * ConnectorDispatcher}. Hermetic — no live Notion workspace.
 */
@Transactional
class NotionConnectorTest extends IntegrationTestBase {

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private ConnectorDispatcher connectorDispatcher;

    private HttpServer server;
    private String baseUrl;

    private static final String TOKEN = "secret-token";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/v1/search", this::handleSearch);
        server.createContext("/v1/blocks/", this::handleBlocks);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void notionConnectorIsRegistered() {
        assertThat(connectorDispatcher.connectorsByType()).containsKey(NotionConnector.CONNECTOR_TYPE);
    }

    @Test
    void ingestsAllPagesAcrossCursorPaginationWithBlockText() throws ConnectorException {
        var source = newSource("notion-ok-kb",
                "{\"token\":\"" + TOKEN + "\",\"pageSize\":2}");

        SyncResult result = connectorDispatcher.sync(source.getId());

        assertThat(result.status()).isEqualTo(SyncResult.Status.SUCCESS);
        List<KnowledgeChunk> chunks = knowledgeChunkRepository.findByKnowledgeSourceId(source.getId());
        assertThat(chunks).extracting(KnowledgeChunk::getLocator)
                .containsExactlyInAnyOrder("p1", "p2", "p3");

        KnowledgeChunk onboarding = chunks.stream()
                .filter(c -> c.getLocator().equals("p1")).findFirst().orElseThrow();
        assertThat(onboarding.getContent())
                .contains("Onboarding")          // title
                .contains("Welcome to the team.") // top-level block
                .contains("Nested detail.");      // child block (recursion)
        assertThat(onboarding.getMetadataJson())
                .contains("\"pageId\":\"p1\"")
                .contains("\"title\":\"Onboarding\"")
                .contains("notion.so/p1");

        KnowledgeChunk todo = chunks.stream()
                .filter(c -> c.getLocator().equals("p3")).findFirst().orElseThrow();
        assertThat(todo.getContent()).contains("[ ] Buy milk");  // to_do rendering
    }

    @Test
    void authFailureOnSearchThrowsConnectorExceptionAndMarksFailed() {
        var source = newSource("notion-bad-auth-kb",
                "{\"token\":\"wrong-token\"}");

        assertThatThrownBy(() -> connectorDispatcher.sync(source.getId()))
                .isInstanceOf(ConnectorException.class);

        KnowledgeSource reloaded = knowledgeSourceRepository.findById(source.getId()).orElseThrow();
        assertThat(reloaded.getLastSyncStatus()).isEqualTo(SyncResult.Status.FAILED.name());
    }

    @Test
    void missingTokenThrowsConnectorException() {
        var source = newSource("notion-no-token-kb", "{\"query\":\"docs\"}");

        assertThatThrownBy(() -> connectorDispatcher.sync(source.getId()))
                .isInstanceOf(ConnectorException.class);
    }

    // --- fake Notion server ---------------------------------------------------

    private void handleSearch(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        boolean secondPage = body.contains("\"start_cursor\":\"cur2\"");

        String json;
        if (!secondPage) {
            json = "{\"object\":\"list\",\"results\":["
                    + pageJson("p1", "Onboarding")
                    + "," + pageJson("p2", "Architecture")
                    + "],\"has_more\":true,\"next_cursor\":\"cur2\"}";
        } else {
            json = "{\"object\":\"list\",\"results\":["
                    + pageJson("p3", "Tasks")
                    + "],\"has_more\":false,\"next_cursor\":null}";
        }
        respond(exchange, json);
    }

    private void handleBlocks(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
        }
        // path = /v1/blocks/{id}/children
        String path = exchange.getRequestURI().getPath();
        String id = path.replace("/v1/blocks/", "").replace("/children", "");
        String json = switch (id) {
            case "p1" -> "{\"object\":\"list\",\"results\":["
                    + paragraph("Welcome to the team.")
                    + "," + toggleWithChild("b-toggle")
                    + "],\"has_more\":false,\"next_cursor\":null}";
            case "b-toggle" -> "{\"object\":\"list\",\"results\":["
                    + paragraph("Nested detail.")
                    + "],\"has_more\":false,\"next_cursor\":null}";
            case "p2" -> "{\"object\":\"list\",\"results\":["
                    + heading("Architecture overview")
                    + "," + paragraph("Services talk over HTTP.")
                    + "],\"has_more\":false,\"next_cursor\":null}";
            case "p3" -> "{\"object\":\"list\",\"results\":["
                    + toDo("Buy milk", false)
                    + "],\"has_more\":false,\"next_cursor\":null}";
            default -> "{\"object\":\"list\",\"results\":[],\"has_more\":false,\"next_cursor\":null}";
        };
        respond(exchange, json);
    }

    private boolean authorized(HttpExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        String version = exchange.getRequestHeaders().getFirst("Notion-Version");
        return ("Bearer " + TOKEN).equals(auth) && version != null && !version.isBlank();
    }

    private void respond(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String pageJson(String id, String title) {
        return "{\"object\":\"page\",\"id\":\"" + id + "\",\"url\":\"https://notion.so/" + id + "\","
                + "\"properties\":{\"Name\":{\"type\":\"title\",\"title\":["
                + "{\"plain_text\":\"" + title + "\"}]}}}";
    }

    private static String paragraph(String text) {
        return "{\"object\":\"block\",\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"paragraph\","
                + "\"has_children\":false,"
                + "\"paragraph\":{\"rich_text\":[{\"plain_text\":\"" + text + "\"}]}}";
    }

    private static String heading(String text) {
        return "{\"object\":\"block\",\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"heading_2\","
                + "\"has_children\":false,"
                + "\"heading_2\":{\"rich_text\":[{\"plain_text\":\"" + text + "\"}]}}";
    }

    private static String toDo(String text, boolean checked) {
        return "{\"object\":\"block\",\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"to_do\","
                + "\"has_children\":false,"
                + "\"to_do\":{\"checked\":" + checked + ",\"rich_text\":[{\"plain_text\":\"" + text + "\"}]}}";
    }

    private static String toggleWithChild(String childId) {
        return "{\"object\":\"block\",\"id\":\"" + childId + "\",\"type\":\"toggle\","
                + "\"has_children\":true,"
                + "\"toggle\":{\"rich_text\":[{\"plain_text\":\"More info\"}]}}";
    }

    private KnowledgeSource newSource(String kbName, String configJson) {
        KnowledgeBase kb = knowledgeBaseService.createSystemBase(
                kbName + "-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                StubRetrievalProvider.PROVIDER_ID,
                null);
        return knowledgeBaseService.addSource(
                kb.getId(),
                NotionConnector.CONNECTOR_TYPE,
                "workspace",
                baseUrl,
                configJson,
                null);
    }
}
