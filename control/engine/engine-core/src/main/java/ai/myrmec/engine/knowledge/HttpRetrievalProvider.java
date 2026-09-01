// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

import ai.myrmec.engine.connection.ConnectionConfig;
import ai.myrmec.engine.connection.ConnectionConfigRepository;
import ai.myrmec.engine.connection.ConnectionConfigVersion;
import ai.myrmec.engine.connection.ConnectionConfigVersionRepository;
import ai.myrmec.engine.secret.SecretPayload;
import ai.myrmec.engine.secret.SecretResolverService;
import ai.myrmec.engine.spi.retrieval.Citation;
import ai.myrmec.engine.spi.retrieval.RetrievalException;
import ai.myrmec.engine.spi.retrieval.RetrievalProvider;
import ai.myrmec.engine.spi.retrieval.RetrievalQuery;
import ai.myrmec.engine.spi.retrieval.RetrievalResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Generic HTTP-based retrieval provider — no stubs, real HTTP calls.
 *
 * <p>Routes retrieval queries to any external HTTP API (RAGFlow, custom
 * embedding service, or any RESTful search backend). The provider resolves
 * the target URL, auth, request field mapping, and response mapping from the
 * {@link KnowledgeProviderVersion} and its linked {@link ConnectionConfig}.</p>
 *
 * <h3>Configuration</h3>
 *
 * <p>The {@link KnowledgeProviderVersion} must have:</p>
 * <ul>
 *   <li>{@code connectionConfigId} → a published {@link ConnectionConfig} of
 *       type {@code HTTP} with a published version containing the base
 *       {@code url} and optional {@code config.headers}.</li>
 *   <li>{@code config} with these keys:
 *     <ul>
 *       <li>{@code endpointPath} — path appended to base URL (e.g. {@code "/api/v1/retrieval"}).
 *           Defaults to {@code "/search"}.</li>
 *       <li>{@code requestMapping} — how to map standard fields to the backend:
 *         <ul>
 *           <li>{@code queryField} — field name for the query string (default: {@code "query"})</li>
 *           <li>{@code topKField} — field name for topK (default: {@code "topK"})</li>
 *           <li>{@code sourceIdField} — field name for knowledgeSourceId (default: {@code "knowledgeSourceId"})</li>
 *           <li>{@code sourceIdIsArray} — wrap sourceId in an array (for RAGFlow's {@code dataset_ids})</li>
 *         </ul>
 *       </li>
 *       <li>{@code responseMapping} — dot-notation paths into the JSON response:
 *         <ul>
 *           <li>{@code hitsPath} — path to the array of hit objects (e.g. {@code "data.chunks"})</li>
 *           <li>{@code passagePath} — path to passage text within each hit (e.g. {@code "content"})</li>
 *           <li>{@code sourceNamePath} — path to source name (e.g. {@code "document_name"})</li>
 *           <li>{@code locatorPath} — path to locator/URL (e.g. {@code "document_id"})</li>
 *           <li>{@code scorePath} — optional path to relevance score (e.g. {@code "similarity"})</li>
 *         </ul>
 *       </li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <h3>RAGFlow example config</h3>
 * <pre>{@code
 * {
 *   "endpointPath": "/api/v1/retrieval",
 *   "requestMapping": {
 *     "queryField": "question",
 *     "topKField": "top_k",
 *     "sourceIdField": "dataset_ids",
 *     "sourceIdIsArray": true
 *   },
 *   "responseMapping": {
 *     "hitsPath": "data.chunks",
 *     "passagePath": "content",
 *     "sourceNamePath": "document_name",
 *     "locatorPath": "document_id",
 *     "scorePath": "similarity"
 *   }
 * }
 * }</pre>
 */
@Slf4j
@Component
public class HttpRetrievalProvider implements RetrievalProvider {

    public static final String PROVIDER_ID = "http-retrieval";

    private final KnowledgeSourceRepository knowledgeSourceRepository;
    private final KnowledgeProviderVersionRepository providerVersionRepository;
    private final ConnectionConfigRepository connectionConfigRepository;
    private final ConnectionConfigVersionRepository connectionConfigVersionRepository;
    private final SecretResolverService secretResolverService;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public HttpRetrievalProvider(
            KnowledgeSourceRepository knowledgeSourceRepository,
            KnowledgeProviderVersionRepository providerVersionRepository,
            ConnectionConfigRepository connectionConfigRepository,
            ConnectionConfigVersionRepository connectionConfigVersionRepository,
            SecretResolverService secretResolverService,
            ObjectMapper objectMapper) {
        this.knowledgeSourceRepository = knowledgeSourceRepository;
        this.providerVersionRepository = providerVersionRepository;
        this.connectionConfigRepository = connectionConfigRepository;
        this.connectionConfigVersionRepository = connectionConfigVersionRepository;
        this.secretResolverService = secretResolverService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public List<RetrievalResult> query(RetrievalQuery query) throws RetrievalException {
        // 1. Resolve the knowledge source → provider version
        KnowledgeSource source = knowledgeSourceRepository.findById(query.knowledgeSourceId())
                .orElse(null);
        if (source == null) {
            log.warn("HttpRetrieval: knowledge source {} not found", query.knowledgeSourceId());
            return List.of();
        }

        KnowledgeProviderVersion providerVersion = providerVersionRepository
                .findById(source.getProviderVersionId()).orElse(null);
        if (providerVersion == null) {
            log.warn("HttpRetrieval: provider version {} not found for source {}",
                    source.getProviderVersionId(), query.knowledgeSourceId());
            return List.of();
        }

        // 2. Resolve the connection config → URL + headers
        UUID connectionConfigId = providerVersion.getConnectionConfigId();
        if (connectionConfigId == null) {
            log.warn("HttpRetrieval: no connection config on provider version {}",
                    providerVersion.getId());
            return List.of();
        }

        ConnectionConfig connectionConfig = connectionConfigRepository.findById(connectionConfigId)
                .orElse(null);
        if (connectionConfig == null) {
            log.warn("HttpRetrieval: connection config {} not found", connectionConfigId);
            return List.of();
        }

        ConnectionConfigVersion connectionVersion = connectionConfigVersionRepository
                .findByConnectionConfigIdAndStatus(connectionConfigId, "PUBLISHED")
                .orElse(null);
        if (connectionVersion == null) {
            log.warn("HttpRetrieval: no published version for connection config {}",
                    connectionConfigId);
            return List.of();
        }

        String baseUrl = connectionVersion.getUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("HttpRetrieval: no URL on connection config version {}",
                    connectionVersion.getId());
            return List.of();
        }

        // 3. Resolve auth
        String authHeader = resolveAuth(connectionConfig, source.getProjectId());

        // 4. Resolve provider config
        Map<String, Object> providerConfig = providerVersion.getConfig();
        if (providerConfig == null) {
            log.warn("HttpRetrieval: no config on provider version {}", providerVersion.getId());
            return List.of();
        }

        // --- Endpoint path ---
        String endpointPath = (String) providerConfig.getOrDefault("endpointPath", "/search");

        // --- Request mapping ---
        @SuppressWarnings("unchecked")
        Map<String, Object> requestMapping = (Map<String, Object>) providerConfig
                .getOrDefault("requestMapping", Map.of());
        String queryField = (String) requestMapping.getOrDefault("queryField", "query");
        String topKField = (String) requestMapping.getOrDefault("topKField", "topK");
        String sourceIdField = (String) requestMapping.getOrDefault("sourceIdField", "knowledgeSourceId");
        boolean sourceIdIsArray = Boolean.TRUE.equals(requestMapping.get("sourceIdIsArray"));

        // --- Response mapping ---
        @SuppressWarnings("unchecked")
        Map<String, Object> responseMapping = (Map<String, Object>) providerConfig
                .get("responseMapping");
        if (responseMapping == null) {
            log.warn("HttpRetrieval: no responseMapping in provider version {} config",
                    providerVersion.getId());
            return List.of();
        }
        String hitsPath = (String) responseMapping.get("hitsPath");
        String passagePath = (String) responseMapping.get("passagePath");
        String sourceNamePath = (String) responseMapping.get("sourceNamePath");
        String locatorPath = (String) responseMapping.get("locatorPath");
        String scorePath = (String) responseMapping.get("scorePath");

        if (hitsPath == null || passagePath == null) {
            log.warn("HttpRetrieval: responseMapping missing hitsPath or passagePath");
            return List.of();
        }

        // 5. Build the request body using the field mapping.
        //    The external ID (e.g. RAGFlow dataset_id) comes from the knowledge
        //    source's config, not the Myrmec UUID.
        String externalId = resolveExternalId(source);
        if (externalId == null) {
            log.warn("HttpRetrieval: no external id in knowledge source {} config", source.getId());
            return List.of();
        }

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put(queryField, query.query());
        requestBody.put(topKField, query.topK());
        if (sourceIdIsArray) {
            ArrayNode arr = objectMapper.createArrayNode();
            arr.add(externalId);
            requestBody.set(sourceIdField, arr);
        } else {
            requestBody.put(sourceIdField, externalId);
        }

        // 6. Build and execute the HTTP request
        String searchUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) + endpointPath
                : baseUrl + endpointPath;

        JsonNode responseBody;
        try {
            String bodyJson = objectMapper.writeValueAsString(requestBody);
            var requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(searchUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson));

            if (authHeader != null) {
                requestBuilder.header("Authorization", authHeader);
            }

            // Add custom headers from connection config
            @SuppressWarnings("unchecked")
            Map<String, Object> connConfig = connectionVersion.getConfig();
            if (connConfig != null && connConfig.containsKey("headers")) {
                @SuppressWarnings("unchecked")
                Map<String, String> headers = (Map<String, String>) connConfig.get("headers");
                headers.forEach(requestBuilder::header);
            }

            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("HttpRetrieval: upstream returned {} for {}: {}",
                        response.statusCode(), searchUrl, response.body());
                return List.of();
            }

            responseBody = objectMapper.readTree(response.body());
        } catch (Exception e) {
            log.warn("HttpRetrieval: request to {} failed: {}", searchUrl, e.getMessage());
            throw new RetrievalException("HTTP retrieval failed: " + e.getMessage(), e);
        }

        // 7. Parse the response
        JsonNode hitsArray = resolvePath(responseBody, hitsPath);
        if (hitsArray == null || !hitsArray.isArray()) {
            log.warn("HttpRetrieval: hitsPath '{}' did not resolve to an array in response",
                    hitsPath);
            return List.of();
        }

        List<RetrievalResult> results = new ArrayList<>();
        for (JsonNode hit : hitsArray) {
            try {
                String passage = resolveText(hit, passagePath);
                if (passage == null || passage.isBlank()) continue;

                String sourceName = resolveText(hit, sourceNamePath);
                if (sourceName == null) sourceName = "Unknown Source";

                String locator = resolveText(hit, locatorPath);
                if (locator == null) locator = "";

                double score = 0.0;
                if (scorePath != null) {
                    JsonNode scoreNode = resolvePath(hit, scorePath);
                    if (scoreNode != null && scoreNode.isNumber()) {
                        score = scoreNode.asDouble();
                    }
                }

                Citation citation = new Citation(
                        UUID.randomUUID(),
                        query.knowledgeSourceId(),
                        sourceName,
                        locator,
                        score);

                results.add(new RetrievalResult(passage, citation));
            } catch (Exception e) {
                log.warn("HttpRetrieval: failed to parse hit: {}", e.getMessage());
            }
        }

        log.info("HttpRetrieval: source {} query '{}' → {} hits",
                query.knowledgeSourceId(), query.query(), results.size());
        return results;
    }

    @Override
    public boolean supportsIngestion() {
        return false;
    }

    /**
     * Resolve the external ID (e.g. RAGFlow dataset_id) from the knowledge
     * source's config. Falls back to the source UUID if no external ID is
     * configured.
     */
    private String resolveExternalId(KnowledgeSource source) {
        Map<String, Object> config = source.getConfig();
        if (config != null && config.containsKey("externalId")) {
            return (String) config.get("externalId");
        }
        // Fallback: use the Myrmec UUID (works for backends that accept any ID)
        return source.getId().toString();
    }

    private String resolveAuth(ConnectionConfig connectionConfig, UUID projectId) {
        UUID secretId = connectionConfig.getCredentialSecretId();
        if (secretId == null) return null;

        try {
            SecretPayload payload = secretResolverService.resolve(secretId, projectId);
            return switch (payload) {
                case SecretPayload.BearerToken bt -> "Bearer " + bt.token();
                case SecretPayload.ApiKey ak -> "Bearer " + ak.key();
                default -> {
                    log.warn("HttpRetrieval: unsupported secret type {} for connection config {}",
                            payload.type(), connectionConfig.getId());
                    yield null;
                }
            };
        } catch (Exception e) {
            log.warn("HttpRetrieval: failed to resolve secret {}: {}",
                    secretId, e.getMessage());
            return null;
        }
    }

    static JsonNode resolvePath(JsonNode root, String path) {
        if (root == null || path == null || path.isBlank()) return null;
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            if (current == null) return null;
            if ("$".equals(segment)) continue;
            current = current.get(segment);
        }
        return current;
    }

    static String resolveText(JsonNode root, String path) {
        JsonNode node = resolvePath(root, path);
        if (node == null) return null;
        return node.isTextual() ? node.asText() : node.toString();
    }
}
