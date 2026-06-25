// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine.secret.SecretResolverService;
import ai.myrmec.engine.spi.retrieval.Citation;
import ai.myrmec.engine.spi.retrieval.RetrievalException;
import ai.myrmec.engine.spi.retrieval.RetrievalProvider;
import ai.myrmec.engine.spi.retrieval.RetrievalQuery;
import ai.myrmec.engine.spi.retrieval.RetrievalResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link RetrievalProvider} (id {@code "ragflow"}) that proxies retrieval to a
 * bundled / external <a href="https://ragflow.io">RAGFlow</a> instance (#26).
 *
 * <p>Per-KB settings (base URL, dataset id, API-key secret ref, top-k/similarity
 * defaults) come from {@code knowledge_bases.provider_config}; the API key is
 * resolved through {@link SecretResolverService} against the KB's owning project
 * scope and never stored inline. The provider is a leaf in the retrieval seam:
 * ACL, audit (#32) and {@code <untrusted>} wrapping happen in
 * {@code AgentRetrievalController} before/after this runs.</p>
 *
 * <p><strong>Threading:</strong> stateless and virtual-thread safe. The JDK
 * {@link HttpClient} is itself thread-safe and a fresh request object is built
 * per {@link #query}.</p>
 *
 * <p><strong>Failure semantics:</strong> connect failure, timeout, non-2xx
 * status, malformed body, missing/unresolvable secret, and malformed
 * {@code provider_config} all surface as {@link RetrievalException}, which the
 * engine demotes to an empty result + warning.</p>
 */
@Component
@Slf4j
public class RagflowRetrievalProvider implements RetrievalProvider {

    public static final String PROVIDER_ID = "ragflow";

    private static final String RETRIEVAL_PATH = "/api/v1/retrieval";

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final SecretResolverService secretResolverService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final int maxTopK;

    public RagflowRetrievalProvider(
            KnowledgeBaseRepository knowledgeBaseRepository,
            SecretResolverService secretResolverService,
            ObjectMapper objectMapper,
            @Value("${myrmec.retrieval.ragflow.default-timeout-ms:5000}") long defaultTimeoutMs,
            @Value("${myrmec.retrieval.ragflow.max-top-k:50}") int maxTopK) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.secretResolverService = secretResolverService;
        this.objectMapper = objectMapper;
        this.requestTimeout = Duration.ofMillis(defaultTimeoutMs);
        this.maxTopK = Math.max(1, maxTopK);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .build();
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public List<RetrievalResult> query(RetrievalQuery query) throws RetrievalException {
        KnowledgeBase kb = knowledgeBaseRepository.findById(query.knowledgeBaseId())
                .orElseThrow(() -> new RetrievalException(
                        "ragflow: knowledge base not found: " + query.knowledgeBaseId()));
        RagflowProviderConfig config = RagflowProviderConfig.from(kb.getProviderConfig(), objectMapper);

        // Secret is scoped to the KB's owning project (null = globals only for
        // GROUP/SYSTEM scopes); missing/unresolvable key is a hard failure.
        String apiKey = secretResolverService
                .resolveReferenceString(config.apiKeySecretRef(), kb.getProjectId())
                .filter(k -> !k.isBlank())
                .orElseThrow(() -> new RetrievalException(
                        "ragflow: api key secret '" + config.apiKeySecretRef() + "' could not be resolved"));

        int topK = Math.min(query.topK(), maxTopK);
        String body = buildRequestBody(config, query.query(), topK);

        HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + RETRIEVAL_PATH))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            // Connect failure / read timeout / TLS error all land here.
            throw new RetrievalException("ragflow: retrieval request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetrievalException("ragflow: retrieval request interrupted", e);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new RetrievalException("ragflow: retrieval returned HTTP " + status);
        }

        final JsonNode root;
        try {
            root = objectMapper.readTree(response.body());
        } catch (JsonProcessingException e) {
            throw new RetrievalException("ragflow: retrieval returned malformed JSON: " + e.getOriginalMessage(), e);
        }
        return mapChunks(root);
    }

    private String buildRequestBody(RagflowProviderConfig config, String question, int topK)
            throws RetrievalException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dataset_ids", List.of(config.datasetId()));
        payload.put("question", question);
        payload.put("top_k", topK);
        payload.put("similarity_threshold", config.similarityThreshold());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new RetrievalException("ragflow: failed to serialise retrieval request", e);
        }
    }

    /**
     * Map RAGFlow's {@code data.chunks[]} (falling back to a top-level
     * {@code chunks[]}) into {@link RetrievalResult}s. Hits with no passage are
     * skipped; an all-skipped / empty response is a valid empty result.
     */
    private List<RetrievalResult> mapChunks(JsonNode root) {
        JsonNode chunks = root.path("data").path("chunks");
        if (!chunks.isArray()) {
            chunks = root.path("chunks");
        }
        List<RetrievalResult> results = new ArrayList<>();
        int index = 0;
        for (JsonNode chunk : chunks) {
            String passage = firstNonBlank(text(chunk, "content_with_weight"), text(chunk, "content"));
            if (passage == null || passage.isBlank()) {
                index++;
                continue;
            }
            String rawChunkId = firstNonBlank(text(chunk, "id"), text(chunk, "chunk_id"));
            String rawDocId = firstNonBlank(text(chunk, "document_id"), text(chunk, "doc_id"));
            String docName = firstNonBlank(
                    text(chunk, "document_keyword"), text(chunk, "docnm_kwd"), text(chunk, "document_name"));
            String url = firstNonBlank(text(chunk, "url"), text(chunk, "document_url"));
            double score = clamp01(doubleValue(chunk, "similarity", "score"));

            UUID chunkId = stableUuid(rawChunkId != null ? rawChunkId : passage + "#" + index);
            UUID sourceId = stableUuid(rawDocId != null ? rawDocId
                    : (docName != null ? docName : passage));
            String sourceName = docName != null ? docName
                    : (rawDocId != null ? rawDocId : "ragflow-source");
            String locator = buildLocator(url, docName, rawDocId, index);

            results.add(new RetrievalResult(passage,
                    new Citation(chunkId, sourceId, sourceName, locator, score)));
            index++;
        }
        return results;
    }

    /**
     * Stable UUID for a citation key: parse if already a UUID, otherwise derive
     * deterministically so #30 side-panel and #32 audit keys stay stable across
     * retrievals.
     */
    private static UUID stableUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String buildLocator(String url, String docName, String rawDocId, int index) {
        String base = firstNonBlank(url, docName, rawDocId, "ragflow");
        return base + "#chunk=" + index;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static double doubleValue(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isNumber()) {
                return value.asDouble();
            }
        }
        return 0.0;
    }

    private static double clamp01(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        return Math.min(value, 1.0);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
