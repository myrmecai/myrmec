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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@link ConfluenceConnector} (#24). Stands up a loopback
 * {@link HttpServer} that mimics the Confluence Cloud content REST API (Basic
 * auth + offset pagination), then drives a sync through {@link
 * ConnectorDispatcher}. Hermetic — no live Atlassian tenant.
 */
@Transactional
class ConfluenceConnectorTest extends IntegrationTestBase {

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private ConnectorDispatcher connectorDispatcher;

    private HttpServer server;
    private String baseUrl;

    private static final String EMAIL = "bot@acme.com";
    private static final String TOKEN = "secret-token";
    private static final String EXPECTED_AUTH =
            "Basic " + Base64.getEncoder().encodeToString((EMAIL + ":" + TOKEN).getBytes(StandardCharsets.UTF_8));

    // pageId -> (title, storage HTML)
    private static final Map<String, String[]> PAGES = new HashMap<>();
    static {
        PAGES.put("1", new String[]{"Onboarding", "<p>Welcome to the <b>team</b>.</p>"});
        PAGES.put("2", new String[]{"Architecture", "<h1>Overview</h1><p>Services talk over HTTP.</p>"});
        PAGES.put("3", new String[]{"Runbook", "<p>Restart the <i>pod</i> if it crashes.</p>"});
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/wiki";
        server.createContext("/wiki/rest/api/content", this::handleContent);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void confluenceConnectorIsRegistered() {
        assertThat(connectorDispatcher.connectorsByType()).containsKey(ConfluenceConnector.CONNECTOR_TYPE);
    }

    @Test
    void ingestsAllPagesAcrossPaginationWithExtractedText() throws ConnectorException {
        var source = newSource("conf-ok-kb",
                "{\"spaceKey\":\"ENG\",\"email\":\"" + EMAIL + "\",\"apiToken\":\"" + TOKEN + "\",\"pageSize\":2}");

        SyncResult result = connectorDispatcher.sync(source.getId());

        assertThat(result.status()).isEqualTo(SyncResult.Status.SUCCESS);
        List<KnowledgeChunk> chunks = knowledgeChunkRepository.findByKnowledgeSourceId(source.getId());
        assertThat(chunks).extracting(KnowledgeChunk::getLocator)
                .containsExactlyInAnyOrder("ENG/1", "ENG/2", "ENG/3");

        KnowledgeChunk arch = chunks.stream()
                .filter(c -> c.getLocator().equals("ENG/2")).findFirst().orElseThrow();
        assertThat(arch.getContent())
                .contains("Architecture")
                .contains("Services talk over HTTP.")
                .doesNotContain("<h1>");
        assertThat(arch.getMetadataJson())
                .contains("\"spaceKey\":\"ENG\"")
                .contains("\"pageId\":\"2\"")
                .contains("\"title\":\"Architecture\"");
    }

    @Test
    void authFailureOnFirstPageThrowsConnectorExceptionAndMarksFailed() {
        var source = newSource("conf-bad-auth-kb",
                "{\"spaceKey\":\"ENG\",\"email\":\"" + EMAIL + "\",\"apiToken\":\"wrong-token\",\"pageSize\":2}");

        assertThatThrownBy(() -> connectorDispatcher.sync(source.getId()))
                .isInstanceOf(ConnectorException.class);

        KnowledgeSource reloaded = knowledgeSourceRepository.findById(source.getId()).orElseThrow();
        assertThat(reloaded.getLastSyncStatus()).isEqualTo(SyncResult.Status.FAILED.name());
    }

    @Test
    void missingSpaceKeyThrowsConnectorException() {
        var source = newSource("conf-no-space-kb",
                "{\"email\":\"" + EMAIL + "\",\"apiToken\":\"" + TOKEN + "\"}");

        assertThatThrownBy(() -> connectorDispatcher.sync(source.getId()))
                .isInstanceOf(ConnectorException.class);
    }

    // --- fake Confluence server ----------------------------------------------

    private void handleContent(HttpExchange exchange) throws IOException {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (!EXPECTED_AUTH.equals(auth)) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
        }
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        int start = Integer.parseInt(query.getOrDefault("start", "0"));
        int limit = Integer.parseInt(query.getOrDefault("limit", "25"));

        List<String> ids = List.of("1", "2", "3");
        StringBuilder results = new StringBuilder();
        int emitted = 0;
        for (int i = start; i < ids.size() && emitted < limit; i++, emitted++) {
            String id = ids.get(i);
            String[] page = PAGES.get(id);
            if (results.length() > 0) {
                results.append(",");
            }
            results.append("{")
                    .append("\"id\":\"").append(id).append("\",")
                    .append("\"type\":\"page\",")
                    .append("\"title\":\"").append(page[0]).append("\",")
                    .append("\"body\":{\"storage\":{\"value\":\"").append(escape(page[1]))
                    .append("\",\"representation\":\"storage\"}},")
                    .append("\"_links\":{\"webui\":\"/spaces/ENG/pages/").append(id).append("\"}")
                    .append("}");
        }
        String json = "{\"results\":[" + results + "],\"start\":" + start
                + ",\"limit\":" + limit + ",\"size\":" + emitted + "}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new HashMap<>();
        if (rawQuery == null) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                params.put(key, value);
            }
        }
        return params;
    }

    private static String escape(String html) {
        return html.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private KnowledgeSource newSource(String kbName, String configJson) {
        KnowledgeBase kb = knowledgeBaseService.createSystemBase(
                kbName + "-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                StubRetrievalProvider.PROVIDER_ID,
                null);
        return knowledgeBaseService.addSource(
                kb.getId(),
                ConfluenceConnector.CONNECTOR_TYPE,
                "space",
                baseUrl,
                configJson,
                null);
    }
}
