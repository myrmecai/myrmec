// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine.knowledge.rag.HttpProviderConfig.RequestMapping;
import ai.myrmec.engine.knowledge.rag.HttpProviderConfig.ResponseMapping;
import ai.myrmec.engine.secret.SecretResolverService;
import ai.myrmec.engine.spi.retrieval.Citation;
import ai.myrmec.engine.spi.retrieval.RetrievalException;
import ai.myrmec.engine.spi.retrieval.RetrievalProvider;
import ai.myrmec.engine.spi.retrieval.RetrievalQuery;
import ai.myrmec.engine.spi.retrieval.RetrievalResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generic HTTP "bring-your-own RAG" {@link RetrievalProvider} (id {@code "http"},
 * #26a). Adapts any admin-configured search endpoint to the retrieval SPI: it
 * builds a JSON request body from {@code requestMapping}, posts it to the
 * configured endpoint with an injected auth header, and extracts hits from the
 * response with the {@code responseMapping} dot-path JSONPath expressions.
 *
 * <p>Per-KB settings (endpoint, method, auth secret ref, request/response field
 * mappings, timeout) come from {@code knowledge_bases.provider_config}; the auth
 * credential is resolved through {@link SecretResolverService} against the KB's
 * owning project scope and never stored inline. The provider is a leaf in the
 * retrieval seam: ACL, audit (#32) and {@code <untrusted>} wrapping happen in
 * {@code AgentRetrievalController} before/after this runs.</p>
 *
 * <p><strong>JSONPath:</strong> the engine carries no JSONPath dependency and
 * the sibling {@code ragflow} provider (#26) maps with plain Jackson, so this
 * provider evaluates the §2.2 expressions with a minimal dot-path resolver
 * ({@link #evalPath}) supporting {@code $.a.b} and {@code $.a[0].b} — sufficient
 * for the documented config and dependency-free.</p>
 *
 * <p><strong>Threading:</strong> stateless and virtual-thread safe. The JDK
 * {@link HttpClient} is thread-safe and a fresh request object is built per
 * {@link #query}; the per-request read timeout comes from the KB config.</p>
 *
 * <p><strong>Partial hits:</strong> {@code passage}, {@code sourceName} and
 * {@code locator} are required by the SPI ({@code @NotBlank}); a hit missing any
 * of them is skipped and logged, not fatal — an all-skipped response is a valid
 * empty result. Missing {@code chunkId}/{@code sourceId} are derived as stable
 * UUIDs from the hit's locator (+index) so citations stay addressable.</p>
 *
 * <p><strong>Failure semantics:</strong> connect failure, timeout, non-2xx
 * status, malformed body, missing/unresolvable secret, and malformed
 * {@code provider_config} all surface as {@link RetrievalException}, which the
 * engine demotes to an empty result + warning.</p>
 */
@Component
@Slf4j
public class HttpRetrievalProvider implements RetrievalProvider {

    public static final String PROVIDER_ID = "http";

    /** Matches one or more trailing array indices in a path token (e.g. {@code [0][1]}). */
    private static final Pattern INDEX = Pattern.compile("\\[(\\d+)\\]");

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final SecretResolverService secretResolverService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public HttpRetrievalProvider(
            KnowledgeBaseRepository knowledgeBaseRepository,
            SecretResolverService secretResolverService,
            ObjectMapper objectMapper,
            @Value("${myrmec.retrieval.http.connect-timeout-ms:5000}") long connectTimeoutMs) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.secretResolverService = secretResolverService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, connectTimeoutMs)))
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
                        "http: knowledge base not found: " + query.knowledgeBaseId()));
        HttpProviderConfig config = HttpProviderConfig.from(kb.getProviderConfig(), objectMapper);

        // Secret is scoped to the KB's owning project (null = globals only for
        // GROUP/SYSTEM scopes); missing/unresolvable credential is a hard failure.
        String secret = secretResolverService
                .resolveReferenceString(config.authSecretRef(), kb.getProjectId())
                .filter(s -> !s.isBlank())
                .orElseThrow(() -> new RetrievalException(
                        "http: auth secret '" + config.authSecretRef() + "' could not be resolved"));

        String body = buildRequestBody(config.requestMapping(), query);
        String authValue = config.authScheme().isBlank()
                ? secret
                : config.authScheme() + " " + secret;

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.endpoint()))
                .timeout(Duration.ofMillis(config.timeoutMs()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header(config.authHeader(), authValue);
        if ("GET".equals(config.method())) {
            builder.method("GET", HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(config.method(),
                    HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        HttpRequest request = builder.build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            // Connect failure / read timeout / TLS error all land here.
            throw new RetrievalException("http: retrieval request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetrievalException("http: retrieval request interrupted", e);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new RetrievalException("http: retrieval returned HTTP " + status);
        }

        final JsonNode root;
        try {
            root = objectMapper.readTree(response.body());
        } catch (JsonProcessingException e) {
            throw new RetrievalException("http: retrieval returned malformed JSON: " + e.getOriginalMessage(), e);
        }
        return mapHits(root, config.responseMapping());
    }

    private String buildRequestBody(RequestMapping mapping, RetrievalQuery query) throws RetrievalException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(mapping.queryField(), query.query());
        payload.put(mapping.topKField(), query.topK());
        if (mapping.filtersField() != null && !query.filters().isEmpty()) {
            payload.put(mapping.filtersField(), query.filters());
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new RetrievalException("http: failed to serialise retrieval request", e);
        }
    }

    /**
     * Map each hit under {@code responseMapping.hitsPath} into a
     * {@link RetrievalResult}. Hits missing any required field
     * ({@code passage}/{@code sourceName}/{@code locator}) are skipped and
     * logged; a non-array {@code hitsPath} or all-skipped response yields an
     * empty result.
     */
    private List<RetrievalResult> mapHits(JsonNode root, ResponseMapping mapping) {
        JsonNode hits = evalPath(root, mapping.hitsPath());
        List<RetrievalResult> results = new ArrayList<>();
        if (!hits.isArray()) {
            return results;
        }
        int index = 0;
        for (JsonNode hit : hits) {
            String passage = text(evalPath(hit, mapping.passagePath()));
            String sourceName = text(evalPath(hit, mapping.sourceNamePath()));
            String locator = text(evalPath(hit, mapping.locatorPath()));
            if (isBlank(passage) || isBlank(sourceName) || isBlank(locator)) {
                log.warn("http: skipping hit {} missing required field (passage/sourceName/locator)", index);
                index++;
                continue;
            }
            String rawChunkId = mapping.chunkIdPath() == null ? null : text(evalPath(hit, mapping.chunkIdPath()));
            String rawSourceId = mapping.sourceIdPath() == null ? null : text(evalPath(hit, mapping.sourceIdPath()));
            double score = mapping.scorePath() == null ? 0.0
                    : clamp01(doubleValue(evalPath(hit, mapping.scorePath())));

            // Missing chunk/source ids derive deterministically from the locator
            // (+index for chunks) so #30 side-panel and #32 audit keys stay stable.
            UUID chunkId = stableUuid(!isBlank(rawChunkId) ? rawChunkId : locator + "#chunk=" + index);
            UUID sourceId = stableUuid(!isBlank(rawSourceId) ? rawSourceId : locator);

            results.add(new RetrievalResult(passage.trim(),
                    new Citation(chunkId, sourceId, sourceName.trim(), locator.trim(), score)));
            index++;
        }
        return results;
    }

    /**
     * Minimal dot-path JSONPath resolver supporting {@code $.a.b.c} and array
     * indices {@code $.a[0].b}. Returns {@link MissingNode} for any absent
     * segment. {@code $} / {@code $.} prefixes are optional.
     */
    private static JsonNode evalPath(JsonNode root, String path) {
        if (root == null || path == null) {
            return MissingNode.getInstance();
        }
        String expr = path.trim();
        if (expr.startsWith("$")) {
            expr = expr.substring(1);
        }
        if (expr.startsWith(".")) {
            expr = expr.substring(1);
        }
        if (expr.isEmpty()) {
            return root;
        }
        JsonNode current = root;
        for (String token : expr.split("\\.")) {
            if (current == null || current.isMissingNode() || current.isNull()) {
                return MissingNode.getInstance();
            }
            int bracket = token.indexOf('[');
            String field = bracket >= 0 ? token.substring(0, bracket) : token;
            if (!field.isEmpty()) {
                current = current.path(field);
            }
            if (bracket >= 0) {
                Matcher matcher = INDEX.matcher(token.substring(bracket));
                while (matcher.find()) {
                    current = current.path(Integer.parseInt(matcher.group(1)));
                }
            }
        }
        return current == null ? MissingNode.getInstance() : current;
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

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isValueNode()) {
            return null;
        }
        return node.asText();
    }

    private static double doubleValue(JsonNode node) {
        return node != null && node.isNumber() ? node.asDouble() : 0.0;
    }

    private static double clamp01(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        return Math.min(value, 1.0);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
