// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine.secret.SecretResolverService;
import ai.myrmec.engine.spi.retrieval.RetrievalException;
import ai.myrmec.engine.spi.retrieval.RetrievalQuery;
import ai.myrmec.engine.spi.retrieval.RetrievalResult;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link RagflowRetrievalProvider} (#26). Stands up a loopback
 * {@link HttpServer} that mimics RAGFlow's {@code POST /api/v1/retrieval}
 * endpoint and drives the provider directly with mocked
 * {@link KnowledgeBaseRepository} + {@link SecretResolverService}. Hermetic
 * (no Spring context, no DB, loopback only).
 */
class RagflowRetrievalProviderTest {

    private static final String API_KEY_REF = "ragflow-api-key";
    private static final String DATASET_ID = "ds-123";
    private static final String API_KEY = "rag-secret-key";

    private HttpServer server;
    private String baseUrl;
    private volatile HttpHandler handler;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private KnowledgeBaseRepository knowledgeBaseRepository;
    private SecretResolverService secretResolverService;

    private UUID kbId;
    private UUID projectId;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/api/v1/retrieval", exchange -> handler.handle(exchange));
        server.start();

        kbId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        knowledgeBaseRepository = mock(KnowledgeBaseRepository.class);
        secretResolverService = mock(SecretResolverService.class);
        when(knowledgeBaseRepository.findById(kbId)).thenReturn(Optional.of(knowledgeBase()));
        when(secretResolverService.resolveReferenceString(eq(API_KEY_REF), any()))
                .thenReturn(Optional.of(API_KEY));
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void happyPathMapsChunksToRetrievalResults() throws RetrievalException {
        UUID chunkUuid = UUID.randomUUID();
        UUID docUuid = UUID.randomUUID();
        handler = respond(200, """
                {
                  "code": 0,
                  "data": {
                    "total": 1,
                    "chunks": [
                      {
                        "id": "%s",
                        "content": "The control engine dispatches tasks to agents.",
                        "document_id": "%s",
                        "document_keyword": "architecture.md",
                        "url": "https://docs/architecture.md",
                        "similarity": 0.87
                      }
                    ]
                  }
                }""".formatted(chunkUuid, docUuid));

        List<RetrievalResult> results = provider(5000).query(query("control engine", 5));

        assertThat(results).hasSize(1);
        RetrievalResult result = results.get(0);
        assertThat(result.passage()).isEqualTo("The control engine dispatches tasks to agents.");
        assertThat(result.citation().chunkId()).isEqualTo(chunkUuid);
        assertThat(result.citation().sourceId()).isEqualTo(docUuid);
        assertThat(result.citation().sourceName()).isEqualTo("architecture.md");
        assertThat(result.citation().locator()).isEqualTo("https://docs/architecture.md#chunk=0");
        assertThat(result.citation().score()).isEqualTo(0.87);
    }

    @Test
    void nonUuidChunkIdDerivesStableUuid() throws RetrievalException {
        handler = respond(200, """
                {
                  "data": {
                    "chunks": [
                      {
                        "id": "chunk-abc",
                        "content": "passage text",
                        "document_id": "doc-xyz",
                        "document_keyword": "notes.md",
                        "similarity": 0.5
                      }
                    ]
                  }
                }""");

        UUID expectedChunkId = UUID.nameUUIDFromBytes("chunk-abc".getBytes(StandardCharsets.UTF_8));
        UUID expectedSourceId = UUID.nameUUIDFromBytes("doc-xyz".getBytes(StandardCharsets.UTF_8));

        List<RetrievalResult> first = provider(5000).query(query("q", 5));
        List<RetrievalResult> second = provider(5000).query(query("q", 5));

        assertThat(first).hasSize(1);
        assertThat(first.get(0).citation().chunkId()).isEqualTo(expectedChunkId);
        assertThat(first.get(0).citation().sourceId()).isEqualTo(expectedSourceId);
        // Deterministic across calls.
        assertThat(second.get(0).citation().chunkId()).isEqualTo(expectedChunkId);
    }

    @Test
    void serverErrorThrowsRetrievalException() {
        handler = respond(503, "{\"error\":\"unavailable\"}");
        assertThatThrownBy(() -> provider(5000).query(query("q", 5)))
                .isInstanceOf(RetrievalException.class)
                .hasMessageContaining("503");
    }

    @Test
    void timeoutThrowsRetrievalException() {
        handler = exchange -> {
            try {
                Thread.sleep(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            writeResponse(exchange, 200, "{\"data\":{\"chunks\":[]}}");
        };
        assertThatThrownBy(() -> provider(200).query(query("q", 5)))
                .isInstanceOf(RetrievalException.class);
    }

    @Test
    void malformedBodyThrowsRetrievalException() {
        handler = respond(200, "not json {{{");
        assertThatThrownBy(() -> provider(5000).query(query("q", 5)))
                .isInstanceOf(RetrievalException.class)
                .hasMessageContaining("malformed");
    }

    @Test
    void missingSecretThrowsRetrievalException() {
        when(secretResolverService.resolveReferenceString(eq(API_KEY_REF), any()))
                .thenReturn(Optional.empty());
        handler = respond(200, "{\"data\":{\"chunks\":[]}}");
        assertThatThrownBy(() -> provider(5000).query(query("q", 5)))
                .isInstanceOf(RetrievalException.class)
                .hasMessageContaining("api key");
    }

    // --- helpers -------------------------------------------------------------

    private RagflowRetrievalProvider provider(long timeoutMs) {
        return new RagflowRetrievalProvider(
                knowledgeBaseRepository, secretResolverService, objectMapper, timeoutMs, 50);
    }

    private RetrievalQuery query(String text, int topK) {
        return new RetrievalQuery(kbId, text, topK, Map.of());
    }

    private KnowledgeBase knowledgeBase() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(kbId);
        kb.setProjectId(projectId);
        kb.setProviderId(RagflowRetrievalProvider.PROVIDER_ID);
        kb.setProviderConfig("""
                {
                  "baseUrl": "%s",
                  "datasetId": "%s",
                  "apiKeySecretRef": "%s",
                  "topKDefault": 8,
                  "similarityThreshold": 0.2
                }""".formatted(baseUrl, DATASET_ID, API_KEY_REF));
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
