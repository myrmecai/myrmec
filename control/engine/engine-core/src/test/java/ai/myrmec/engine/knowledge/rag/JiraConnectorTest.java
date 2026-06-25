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
 * Integration test for {@link JiraConnector} (#24). Stands up a loopback
 * {@link HttpServer} that mimics the Jira Cloud search REST API (Basic auth +
 * ADF bodies + offset pagination), then drives a sync through {@link
 * ConnectorDispatcher}. Hermetic — no live Atlassian tenant.
 */
@Transactional
class JiraConnectorTest extends IntegrationTestBase {

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

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/rest/api/3/search", this::handleSearch);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void jiraConnectorIsRegistered() {
        assertThat(connectorDispatcher.connectorsByType()).containsKey(JiraConnector.CONNECTOR_TYPE);
    }

    @Test
    void ingestsAllIssuesAcrossPaginationWithAdfText() throws ConnectorException {
        var source = newSource("jira-ok-kb",
                "{\"projectKey\":\"ENG\",\"email\":\"" + EMAIL + "\",\"apiToken\":\"" + TOKEN + "\",\"pageSize\":2}");

        SyncResult result = connectorDispatcher.sync(source.getId());

        assertThat(result.status()).isEqualTo(SyncResult.Status.SUCCESS);
        List<KnowledgeChunk> chunks = knowledgeChunkRepository.findByKnowledgeSourceId(source.getId());
        assertThat(chunks).extracting(KnowledgeChunk::getLocator)
                .containsExactlyInAnyOrder("ENG-1", "ENG-2", "ENG-3");

        KnowledgeChunk first = chunks.stream()
                .filter(c -> c.getLocator().equals("ENG-1")).findFirst().orElseThrow();
        assertThat(first.getContent())
                .contains("Login is broken")          // summary
                .contains("Users cannot sign in.")    // ADF description text
                .contains("Please fix urgently.");    // comment text
        assertThat(first.getContent()).doesNotContain("\"type\"");  // ADF JSON not leaked
        assertThat(first.getMetadataJson())
                .contains("\"projectKey\":\"ENG\"")
                .contains("\"issueKey\":\"ENG-1\"")
                .contains("\"status\":\"Open\"")
                .contains("\"issueType\":\"Bug\"")
                .contains("/browse/ENG-1");
    }

    @Test
    void commentsAreOmittedWhenIncludeCommentsFalse() throws ConnectorException {
        var source = newSource("jira-nocomments-kb",
                "{\"projectKey\":\"ENG\",\"email\":\"" + EMAIL + "\",\"apiToken\":\"" + TOKEN
                        + "\",\"includeComments\":false}");

        connectorDispatcher.sync(source.getId());

        KnowledgeChunk first = knowledgeChunkRepository.findByKnowledgeSourceId(source.getId()).stream()
                .filter(c -> c.getLocator().equals("ENG-1")).findFirst().orElseThrow();
        assertThat(first.getContent())
                .contains("Users cannot sign in.")
                .doesNotContain("Please fix urgently.");
    }

    @Test
    void authFailureOnFirstPageThrowsConnectorExceptionAndMarksFailed() {
        var source = newSource("jira-bad-auth-kb",
                "{\"projectKey\":\"ENG\",\"email\":\"" + EMAIL + "\",\"apiToken\":\"wrong-token\"}");

        assertThatThrownBy(() -> connectorDispatcher.sync(source.getId()))
                .isInstanceOf(ConnectorException.class);

        KnowledgeSource reloaded = knowledgeSourceRepository.findById(source.getId()).orElseThrow();
        assertThat(reloaded.getLastSyncStatus()).isEqualTo(SyncResult.Status.FAILED.name());
    }

    @Test
    void missingProjectAndJqlThrowsConnectorException() {
        var source = newSource("jira-no-target-kb",
                "{\"email\":\"" + EMAIL + "\",\"apiToken\":\"" + TOKEN + "\"}");

        assertThatThrownBy(() -> connectorDispatcher.sync(source.getId()))
                .isInstanceOf(ConnectorException.class);
    }

    // --- fake Jira server ----------------------------------------------------

    private void handleSearch(HttpExchange exchange) throws IOException {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (!EXPECTED_AUTH.equals(auth)) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
        }
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        int startAt = Integer.parseInt(query.getOrDefault("startAt", "0"));
        int maxResults = Integer.parseInt(query.getOrDefault("maxResults", "50"));

        List<String> keys = List.of("ENG-1", "ENG-2", "ENG-3");
        StringBuilder issues = new StringBuilder();
        int emitted = 0;
        for (int i = startAt; i < keys.size() && emitted < maxResults; i++, emitted++) {
            if (issues.length() > 0) {
                issues.append(",");
            }
            issues.append(issueJson(keys.get(i), i + 1));
        }
        String json = "{\"issues\":[" + issues + "],\"startAt\":" + startAt
                + ",\"maxResults\":" + maxResults + ",\"total\":" + keys.size() + "}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String issueJson(String key, int n) {
        String summary = n == 1 ? "Login is broken" : "Issue number " + n;
        String descText = n == 1 ? "Users cannot sign in." : "Body of issue " + n + ".";
        String commentText = n == 1 ? "Please fix urgently." : "Looks fine to me.";
        // ADF doc with a single paragraph; comment also ADF.
        String description = "{\"type\":\"doc\",\"version\":1,\"content\":["
                + "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"" + descText + "\"}]}]}";
        String comment = "{\"type\":\"doc\",\"version\":1,\"content\":["
                + "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"" + commentText + "\"}]}]}";
        return "{"
                + "\"key\":\"" + key + "\","
                + "\"fields\":{"
                + "\"summary\":\"" + summary + "\","
                + "\"description\":" + description + ","
                + "\"status\":{\"name\":\"Open\"},"
                + "\"issuetype\":{\"name\":\"Bug\"},"
                + "\"project\":{\"key\":\"ENG\"},"
                + "\"comment\":{\"comments\":[{\"author\":{\"displayName\":\"Ada\"},\"body\":" + comment + "}]}"
                + "}}";
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new HashMap<>();
        if (rawQuery == null) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                params.put(k, v);
            }
        }
        return params;
    }

    private KnowledgeSource newSource(String kbName, String configJson) {
        KnowledgeBase kb = knowledgeBaseService.createSystemBase(
                kbName + "-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                StubRetrievalProvider.PROVIDER_ID,
                null);
        return knowledgeBaseService.addSource(
                kb.getId(),
                JiraConnector.CONNECTOR_TYPE,
                "project",
                baseUrl,
                configJson,
                null);
    }
}
