// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine.spi.retrieval.RetrievalException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Typed view of a {@code http} knowledge base's {@code provider_config} JSON
 * (#26a) — the generic "bring-your-own RAG" adapter. Deserialized with the
 * engine's shared Jackson mapper.
 *
 * <p>Schema (per design §2.2):</p>
 * <pre>{@code
 * {
 *   "endpoint": "https://rag.acme.internal/search",  // required absolute http(s) URL
 *   "method": "POST",                                  // optional, default POST
 *   "authSecretRef": "acme-rag-token",                 // required secret ref (UUID or name)
 *   "authHeader": "Authorization",                     // optional, default Authorization
 *   "authScheme": "Bearer",                            // optional, default Bearer ("" = raw token)
 *   "requestMapping": {
 *     "queryField": "query",                           // optional, default query
 *     "topKField": "top_k",                            // optional, default top_k
 *     "filtersField": "filters"                        // optional; omitted body key when absent
 *   },
 *   "responseMapping": {
 *     "hitsPath": "$.results",                         // required: array of hits (root-relative)
 *     "passagePath": "$.text",                         // required (hit-relative)
 *     "sourceNamePath": "$.source",                    // required (hit-relative)
 *     "locatorPath": "$.url",                          // required (hit-relative)
 *     "sourceIdPath": "$.source_id",                   // optional; UUID derived from locator when absent
 *     "chunkIdPath": "$.chunk_id",                     // optional; UUID derived from locator+index when absent
 *     "scorePath": "$.score"                           // optional; 0.0 when absent
 *   },
 *   "timeoutMs": 5000                                  // optional, default 5000
 * }
 * }</pre>
 *
 * <p>The {@code *Path} values are simple dot-notation JSONPath expressions
 * (e.g. {@code $.results}, {@code $.data.hits[0]}); they are evaluated by
 * {@link HttpRetrievalProvider} against the response root ({@code hitsPath}) or
 * each hit (all other paths). Malformed JSON or a blank required field raises
 * {@link RetrievalException}, which the dispatcher path demotes to an empty
 * result + warning so a misconfigured KB never crashes a turn.</p>
 *
 * @param endpoint        absolute {@code http(s)} URL the search request is sent to.
 * @param method          HTTP method (upper-cased), default {@code POST}.
 * @param authSecretRef   secret reference resolved to the auth credential.
 * @param authHeader      header name the credential is injected into.
 * @param authScheme      scheme prefix prepended to the credential ({@code Bearer});
 *                        blank means the raw resolved secret is sent verbatim.
 * @param requestMapping  field names used when building the request body.
 * @param responseMapping JSONPath expressions used when reading the response.
 * @param timeoutMs       per-request read timeout in milliseconds (&gt; 0).
 */
public record HttpProviderConfig(
        String endpoint,
        String method,
        String authSecretRef,
        String authHeader,
        String authScheme,
        RequestMapping requestMapping,
        ResponseMapping responseMapping,
        long timeoutMs
) {

    private static final String DEFAULT_METHOD = "POST";
    private static final String DEFAULT_AUTH_HEADER = "Authorization";
    private static final String DEFAULT_AUTH_SCHEME = "Bearer";
    private static final String DEFAULT_QUERY_FIELD = "query";
    private static final String DEFAULT_TOP_K_FIELD = "top_k";
    private static final long DEFAULT_TIMEOUT_MS = 5000L;

    /** Body field names for the outbound search request. */
    public record RequestMapping(String queryField, String topKField, String filtersField) {
    }

    /**
     * Dot-notation JSONPath expressions read from the response. {@code hitsPath}
     * is root-relative and must resolve to an array; the rest are hit-relative.
     */
    public record ResponseMapping(
            String hitsPath,
            String passagePath,
            String sourceIdPath,
            String sourceNamePath,
            String locatorPath,
            String chunkIdPath,
            String scorePath
    ) {
    }

    /**
     * Parse and validate {@code provider_config} JSON. Required fields
     * ({@code endpoint}, {@code authSecretRef}, and the {@code responseMapping}
     * {@code hitsPath}/{@code passagePath}/{@code sourceNamePath}/{@code locatorPath})
     * must be present and non-blank; everything else falls back to its documented
     * default.
     *
     * @throws RetrievalException if the JSON is blank, malformed, the endpoint is
     *                            not an absolute http(s) URL, or a required field
     *                            is missing/blank.
     */
    public static HttpProviderConfig from(String json, ObjectMapper mapper) throws RetrievalException {
        if (json == null || json.isBlank()) {
            throw new RetrievalException("http provider_config is missing");
        }
        final JsonNode node;
        try {
            node = mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new RetrievalException("http provider_config is not valid JSON: " + e.getOriginalMessage(), e);
        }

        String endpoint = requireText(node, "endpoint");
        validateEndpoint(endpoint);
        String method = optText(node, "method", DEFAULT_METHOD).toUpperCase(Locale.ROOT);
        String authSecretRef = requireText(node, "authSecretRef");
        String authHeader = optText(node, "authHeader", DEFAULT_AUTH_HEADER);
        // authScheme may legitimately be empty (raw-token header) when explicitly "".
        String authScheme = node.hasNonNull("authScheme")
                ? node.get("authScheme").asText().trim()
                : DEFAULT_AUTH_SCHEME;

        JsonNode reqNode = node.path("requestMapping");
        RequestMapping requestMapping = new RequestMapping(
                optText(reqNode, "queryField", DEFAULT_QUERY_FIELD),
                optText(reqNode, "topKField", DEFAULT_TOP_K_FIELD),
                optText(reqNode, "filtersField", null));

        JsonNode respNode = node.path("responseMapping");
        if (!respNode.isObject()) {
            throw new RetrievalException("http provider_config: 'responseMapping' is required");
        }
        ResponseMapping responseMapping = new ResponseMapping(
                requireText(respNode, "hitsPath"),
                requireText(respNode, "passagePath"),
                optText(respNode, "sourceIdPath", null),
                requireText(respNode, "sourceNamePath"),
                requireText(respNode, "locatorPath"),
                optText(respNode, "chunkIdPath", null),
                optText(respNode, "scorePath", null));

        long timeoutMs = DEFAULT_TIMEOUT_MS;
        if (node.hasNonNull("timeoutMs")) {
            timeoutMs = node.get("timeoutMs").asLong(DEFAULT_TIMEOUT_MS);
            if (timeoutMs <= 0) {
                timeoutMs = DEFAULT_TIMEOUT_MS;
            }
        }

        return new HttpProviderConfig(
                endpoint.trim(), method, authSecretRef.trim(), authHeader, authScheme,
                requestMapping, responseMapping, timeoutMs);
    }

    private static void validateEndpoint(String endpoint) throws RetrievalException {
        final URI uri;
        try {
            uri = new URI(endpoint.trim());
        } catch (URISyntaxException e) {
            throw new RetrievalException("http provider_config: 'endpoint' is not a valid URL", e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getHost() == null) {
            throw new RetrievalException(
                    "http provider_config: 'endpoint' must be an absolute http(s) URL");
        }
    }

    private static String requireText(JsonNode node, String field) throws RetrievalException {
        JsonNode value = node.get(field);
        String text = value == null || value.isNull() ? null : value.asText();
        if (text == null || text.isBlank()) {
            throw new RetrievalException("http provider_config: '" + field + "' is required");
        }
        return text;
    }

    private static String optText(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        String text = value.asText();
        return text.isBlank() ? fallback : text.trim();
    }
}
