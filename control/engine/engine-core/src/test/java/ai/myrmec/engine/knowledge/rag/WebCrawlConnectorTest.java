package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.spi.connector.ConnectorException;
import ai.myrmec.engine.spi.connector.SyncResult;
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
 * Integration test for {@link WebCrawlConnector} (#23). Stands up a real local
 * {@link HttpServer} serving a small linked site, then drives a sync through
 * {@link ConnectorDispatcher} against {@code http://127.0.0.1:<port>/} so the
 * test is hermetic (loopback only). Verifies BFS depth limiting, same-domain
 * containment, HTML→text extraction, the page cap, and seed-failure handling.
 */
@Transactional
class WebCrawlConnectorTest extends IntegrationTestBase {

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private KnowledgeSourceRepository knowledgeSourceRepository;

    @Autowired
    private KnowledgeChunkRepository knowledgeChunkRepository;

    @Autowired
    private ConnectorDispatcher connectorDispatcher;

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startSite() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        // Home links to /a, /b, and an off-domain page that must never be fetched.
        serve("/", """
                <html><head><title>Home</title></head><body>
                <h1>Welcome</h1>
                <a href="/a">Page A</a>
                <a href="/b">Page B</a>
                <a href="http://external.example.com/x">External</a>
                </body></html>""");
        // /a links deeper to /c.
        serve("/a", """
                <html><head><title>A</title></head><body>
                <script>var x = 1;</script>
                <p>Alpha content here.</p>
                <a href="/c">Page C</a>
                </body></html>""");
        serve("/b", "<html><head><title>B</title></head><body><p>Beta content.</p></body></html>");
        serve("/c", "<html><head><title>C</title></head><body><p>Gamma content.</p></body></html>");
        server.start();
    }

    @AfterEach
    void stopSite() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void webCrawlConnectorIsRegistered() {
        assertThat(connectorDispatcher.connectorsByType()).containsKey(WebCrawlConnector.CONNECTOR_TYPE);
    }

    @Test
    void depthOneCrawlsSeedAndDirectLinksOnly() throws ConnectorException {
        var source = newWebSource("web-d1-kb", "{\"maxDepth\":1}");

        SyncResult result = connectorDispatcher.sync(source.getId());

        assertThat(result.status()).isEqualTo(SyncResult.Status.SUCCESS);
        List<KnowledgeChunk> chunks = knowledgeChunkRepository.findByKnowledgeSourceId(source.getId());
        assertThat(chunks).extracting(KnowledgeChunk::getLocator)
                .containsExactlyInAnyOrder(baseUrl + "/", baseUrl + "/a", baseUrl + "/b");
    }

    @Test
    void depthTwoFollowsLinksTransitivelyWithinDomain() throws ConnectorException {
        var source = newWebSource("web-d2-kb", "{\"maxDepth\":2}");

        SyncResult result = connectorDispatcher.sync(source.getId());

        assertThat(result.status()).isEqualTo(SyncResult.Status.SUCCESS);
        List<KnowledgeChunk> chunks = knowledgeChunkRepository.findByKnowledgeSourceId(source.getId());
        assertThat(chunks).extracting(KnowledgeChunk::getLocator)
                .containsExactlyInAnyOrder(
                        baseUrl + "/", baseUrl + "/a", baseUrl + "/b", baseUrl + "/c");
        // External link is never fetched (same-domain default).
        assertThat(chunks).noneMatch(c -> c.getLocator().contains("external.example.com"));
    }

    @Test
    void extractsVisibleTextAndDropsScripts() throws ConnectorException {
        var source = newWebSource("web-text-kb", "{\"maxDepth\":1}");

        connectorDispatcher.sync(source.getId());

        List<KnowledgeChunk> chunks = knowledgeChunkRepository.findByKnowledgeSourceId(source.getId());
        KnowledgeChunk pageA = chunks.stream()
                .filter(c -> c.getLocator().equals(baseUrl + "/a")).findFirst().orElseThrow();
        assertThat(pageA.getContent()).contains("Alpha content here.");
        assertThat(pageA.getContent()).doesNotContain("var x = 1");
        assertThat(pageA.getMetadataJson()).contains("\"title\":\"A\"");
    }

    @Test
    void maxPagesCapsTheCrawl() throws ConnectorException {
        var source = newWebSource("web-cap-kb", "{\"maxDepth\":5,\"maxPages\":2}");

        SyncResult result = connectorDispatcher.sync(source.getId());

        assertThat(result.chunksEmitted()).isEqualTo(2);
        List<KnowledgeChunk> chunks = knowledgeChunkRepository.findByKnowledgeSourceId(source.getId());
        assertThat(chunks).hasSize(2);
    }

    @Test
    void unreachableSeedThrowsConnectorExceptionAndMarksFailed() {
        // Point at a closed loopback port (server stopped after capturing the URL).
        var source = newWebSourceWithUri("web-bad-kb",
                "http://127.0.0.1:1/", "{\"timeoutSeconds\":2}");

        assertThatThrownBy(() -> connectorDispatcher.sync(source.getId()))
                .isInstanceOf(ConnectorException.class);

        KnowledgeSource reloaded = knowledgeSourceRepository.findById(source.getId()).orElseThrow();
        assertThat(reloaded.getLastSyncStatus()).isEqualTo(SyncResult.Status.FAILED.name());
    }

    // --- helpers -------------------------------------------------------------

    private void serve(String path, String body) {
        server.createContext(path, exchange -> {
            // Exact-path match only — the JDK server matches by prefix otherwise.
            if (!exchange.getRequestURI().getPath().equals(path)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    private KnowledgeSource newWebSource(String kbName, String configJson) {
        return newWebSourceWithUri(kbName, baseUrl + "/", configJson);
    }

    private KnowledgeSource newWebSourceWithUri(String kbName, String uri, String configJson) {
        KnowledgeBase kb = knowledgeBaseService.createSystemBase(
                kbName + "-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                StubRetrievalProvider.PROVIDER_ID,
                null);
        return knowledgeBaseService.addSource(
                kb.getId(),
                WebCrawlConnector.CONNECTOR_TYPE,
                "site",
                uri,
                configJson,
                null);
    }
}
