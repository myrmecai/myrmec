package ai.myrmec.engine.knowledge.rag.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/agent/retrieve}. Mirrors
 * {@link ai.myrmec.engine.spi.retrieval.RetrievalQuery} but stays at the
 * controller layer so the SPI value type doesn't leak through OpenAPI /
 * jackson reflection.
 */
public record RetrievalRequest(
    @NotNull UUID knowledgeBaseId,
    @NotBlank String query,
    @Min(1) int topK,
    Map<String, String> filters
) {
}
