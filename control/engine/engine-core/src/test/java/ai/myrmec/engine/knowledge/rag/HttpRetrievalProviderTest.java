// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine.secret.SecretResolverService;
import ai.myrmec.engine.spi.retrieval.RetrievalException;
import ai.myrmec.engine.spi.retrieval.RetrievalQuery;
import ai.myrmec.engine.spi.retrieval.RetrievalResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link HttpRetrievalProvider} (#26a). Stands up a loopback
 * {@link HttpServer} that mimics a BYO-RAG search endpoint and drives the
 * provider directly with mocked {@link KnowledgeBaseRepository} +
 * {@link SecretResolverService}. Hermetic (no Spring context, no DB, loopback
 * only) — mirrors {@code RagflowRetrievalProviderTest}.
 */
class HttpRetrievalProviderTest {

    private static final String AUTH_SECRET_REF = "acme-rag-token";
    private static final String AUTH_TOKEN = "rag-secret-token";
    private static final String SEARCH_PATH = "/search";

    private HttpServer server;
    private String endpoint;
    private volatile HttpHandler handler;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private KnowledgeBaseRepository knowledgeBaseRepository;
    private SecretResolverService secretResolverService;

    private UUID kbId;
    private UUID projectId;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + SEARCH_PATH;
        server.createContext(SEARCH_PATH, exchange -> {
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            handler.handle(exchange);
        });
        server.start();

        kbId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        knowledgeBaseRepository = mock(KnowledgeBaseRepository.class);
        secretResolverService = mock(SecretResolverService.class);
        when(knowledgeBaseRepository.findById(kbId)).thenReturn(Optional.of(knowledgeBase(5000)));
        when(secretResolverService.resolveReferenceString(eq(AUTH_SECRET_REF), any()))
                .thenReturn(Optional.of(AUTH_TOKEN));
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void requestMappingBuildsBodyAndAuthHeader() throws RetrievalException, IOException {
        handler = respond(200, "{\"results\":[]}");

        provider().query(new RetrievalQuery(kbId, "control engine", 7, Map.of("tag", "docs")));

        assertThat(lastAuthHeader.get()).isEqualTo("Bearer " + AUTH_TOKEN);
        JsonNode body = objectMapper.readTree(lastBody.get());
        assertThat(body.get("query").asText()).isEqualTo("control engine");
        assertThat(body.get("top_k").asInt()).isEqualTo(7);
        assertThat(body.get("filters").get("tag").asText()).isEqualTo("docs");
    }

    @Test
    void responseMappingExtractsAllFields() throws RetrievalException {
        UUID chunkUuid = UUID.randomUUID();
        UUID sourceUuid = UUID.randomUUID();
        handler = respond(200, """
                {
                  "results": [
                    {
                      "text": "The control engine dispatches tasks to agents.",
                      "chunk_id": "%s",
                      "source_id": "%s",
                      "source": "architecture.md",
                      "url": "https://docs/architecture.md",
                      "score": 0.91
                    }
                  ]
                }""".formatted(chunkUuid, sourceUuid));

        List<RetrievalResult> results = provider().query(query("control engine", 5));

        assertThat(results).hasSize(1);
        RetrievalResult result = results.get(0);
        assertThat(result.passage()).isEqualTo("The control engine dispatches tasks to agents.");
        assertThat(result.citation().chunkId()).isEqualTo(chunkUuid);
        assertThat(result.citation().sourceId()).isEqualTo(sourceUuid);
        assertThat(result.citation().sourceName()).isEqualTo("architecture.md");
        assertThat(result.citation().locator()).isEqualTo("https://docs/architecture.md");
        assertThat(result.citation().score()).isEqualTo(0.91);
    }

    @Test
    void partialHitMissingRequiredFieldIsSkipped() throws RetrievalException {
        handler = respond(200, """
                {
                  "results": [
                    {
                      "text": "kept passage",
                      "source": "kept.md",
                      "url": "https://docs/kept.md",
                      "score": 0.5
                    },
                    {
                      "text": "dropped: no locator",
                      "source": "broken.md",
                      "score": 0.4
                    }
                  ]
                }""");

        List<RetrievalResult> results = provider().query(query("q", 5));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passage()).isEqualTo("kept passage");
        assertThat(results.get(0).citation().sourceName()).isEqualTo("kept.md");
    }

    @Test
    void missingChunkAndSourceIdsDeriveStableUuid() throws RetrievalException {
        handler = respond(200, """
                {
                  "results": [
                    {
                      "text": "passage without ids",
                      "source": "notes.md",
                      "url": "https://docs/notes.md",
                      "score": 0.3
                    }
                  ]
                }""");

        UUID expectedChunkId =
                UUID.nameUUIDFromBytes("https://docs/notes.md#chunk=0".getBytes(StandardCharsets.UTF_8));
        UUID expectedSourceId =
                UUID.nameUUIDFromBytes("https://docs/notes.md".getBytes(StandardCharsets.UTF_8));

        List<RetrievalResult> first = provider().query(query("q", 5));
        List<RetrievalResult> second = provider().query(query("q", 5));

        assertThat(first).hasSize(1);
        assertThat(first.get(0).citation().chunkId()).isEqualTo(expectedChunkId);
        assertThat(first.get(0).citation().sourceId()).isEqualTo(expectedSourceId);
        // Deterministic across calls.
        assertThat(second.get(0).citation().chunkId()).isEqualTo(expectedChunkId);
    }

    @Test
    void serverErrorThrowsRetrievalException() {
        handler = respond(503, "{\"error\":\"unavailable\"}");
        assertThatThrownBy(() -> provider().query(query("q", 5)))
                .isInstanceOf(RetrievalException.class)
                .hasMessageContaining("503");
    }

    @Test
    void timeoutThrowsRetrievalException() throws IOException {
        when(knowledgeBaseRepository.findById(kbId)).thenReturn(Optional.of(knowledgeBase(200)));
        handler = exchange -> {
            try {
                Thread.sleep(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            writeResponse(exchange, 200, "{\"results\":[]}");
        };
        assertThatThrownBy(() -> provider().query(query("q", 5)))
                .isInstanceOf(RetrievalException.class);
    }

    @Test
    void malformedBodyThrowsRetrievalException() {
        handler = respond(200, "not json {{{");
        assertThatThrownBy(() -> provider().query(query("q", 5)))
                .isInstanceOf(RetrievalException.class)
                .hasMessageContaining("malformed");
    }

    @Test
    void missingSecretThrowsRetrievalException() {
        when(secretResolverService.resolveReferenceString(eq(AUTH_SECRET_REF), any()))
                .thenReturn(Optional.empty());
        handler = respond(200, "{\"results\":[]}");
        assertThatThrownBy(() -> provider().query(query("q", 5)))
                .isInstanceOf(RetrievalException.class)
                .hasMessageContaining("auth secret");
    }

    // --- helpers -------------------------------------------------------------

    private HttpRetrievalProvider provider() {
        return new HttpRetrievalProvider(
                knowledgeBaseRepository, secretResolverService, objectMapper, 5000);
    }

    private RetrievalQuery query(String text, int topK) {
        return new RetrievalQuery(kbId, text, topK, Map.of());
    }

    private KnowledgeBase knowledgeBase(long timeoutMs) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(kbId);
        kb.setProjectId(projectId);
        kb.setProviderId(HttpRetrievalProvider.PROVIDER_ID);
        kb.setProviderConfig("""
                {
                  "endpoint": "%s",
                  "method": "POST",
                  "authSecretRef": "%s",
                  "authHeader": "Authorization",
                  "authScheme": "Bearer",
                  "requestMapping": {
                    "queryField": "query",
                    "topKField": "top_k",
                    "filtersField": "filters"
                  },
                  "responseMapping": {
                    "hitsPath": "$.results",
                    "passagePath": "$.text",
                    "sourceIdPath": "$.source_id",
                    "sourceNamePath": "$.source",
                    "locatorPath": "$.url",
                    "chunkIdPath": "$.chunk_id",
                    "scorePath": "$.score"
                  },
                  "timeoutMs": %d
                }""".formatted(endpoint, AUTH_SECRET_REF, timeoutMs));
        return kb;
    }

    private HttpHandler respond(int status, String body) {
        return exchange -> writeResponse(exchange, status, body);
    }

    private static void writeResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
