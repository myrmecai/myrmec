// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine.spi.retrieval.RetrievalException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Typed view of a {@code ragflow} knowledge base's {@code provider_config}
 * JSON (#26). Deserialized with the engine's shared Jackson mapper.
 *
 * <p>Schema (per design §2.1):</p>
 * <pre>{@code
 * {
 *   "baseUrl": "http://ragflow:9380",   // required RAGFlow base URL
 *   "datasetId": "<dataset id>",         // required RAGFlow dataset/kb id
 *   "apiKeySecretRef": "ragflow-api-key",// required secret ref (UUID or name)
 *   "topKDefault": 8,                     // optional, default 8
 *   "similarityThreshold": 0.2            // optional, default 0.2
 * }
 * }</pre>
 *
 * <p>Malformed JSON or a blank required field raises {@link RetrievalException};
 * the dispatcher path demotes that to an empty result + warning so a
 * misconfigured KB never crashes a turn.</p>
 *
 * @param baseUrl             RAGFlow base URL, trailing slash stripped.
 * @param datasetId           RAGFlow dataset id queried via {@code dataset_ids}.
 * @param apiKeySecretRef     secret reference resolved to the bearer API key.
 * @param topKDefault         per-KB default top-k (>= 1); used when the caller
 *                            does not constrain it further.
 * @param similarityThreshold minimum similarity passed to RAGFlow in [0, 1].
 */
public record RagflowProviderConfig(
        String baseUrl,
        String datasetId,
        String apiKeySecretRef,
        int topKDefault,
        double similarityThreshold
) {

    private static final int DEFAULT_TOP_K = 8;
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.2;

    /**
     * Parse and validate {@code provider_config} JSON. Required string fields
     * must be present and non-blank; numeric fields fall back to their
     * documented defaults when absent.
     *
     * @throws RetrievalException if the JSON is blank, malformed, or a required
     *                            field is missing/blank.
     */
    public static RagflowProviderConfig from(String json, ObjectMapper mapper) throws RetrievalException {
        if (json == null || json.isBlank()) {
            throw new RetrievalException("ragflow provider_config is missing");
        }
        final JsonNode node;
        try {
            node = mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new RetrievalException("ragflow provider_config is not valid JSON: " + e.getOriginalMessage(), e);
        }
        String baseUrl = requireText(node, "baseUrl");
        String datasetId = requireText(node, "datasetId");
        String apiKeySecretRef = requireText(node, "apiKeySecretRef");

        int topK = DEFAULT_TOP_K;
        if (node.hasNonNull("topKDefault")) {
            topK = node.get("topKDefault").asInt(DEFAULT_TOP_K);
            if (topK < 1) {
                topK = DEFAULT_TOP_K;
            }
        }
        double similarityThreshold = node.hasNonNull("similarityThreshold")
                ? node.get("similarityThreshold").asDouble(DEFAULT_SIMILARITY_THRESHOLD)
                : DEFAULT_SIMILARITY_THRESHOLD;

        return new RagflowProviderConfig(
                stripTrailingSlash(baseUrl.trim()),
                datasetId.trim(),
                apiKeySecretRef.trim(),
                topK,
                similarityThreshold);
    }

    private static String requireText(JsonNode node, String field) throws RetrievalException {
        JsonNode value = node.get(field);
        String text = value == null || value.isNull() ? null : value.asText();
        if (text == null || text.isBlank()) {
            throw new RetrievalException("ragflow provider_config: '" + field + "' is required");
        }
        return text;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
