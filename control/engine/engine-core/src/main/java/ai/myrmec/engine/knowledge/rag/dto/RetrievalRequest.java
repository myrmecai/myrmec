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
 *
 * <p>{@code taskId} / {@code attemptId} are optional audit context: when the
 * caller supplies a task id the engine records a {@code RETRIEVAL} execution
 * event (feature #32) so an AUDITOR can later replay which knowledge fed an
 * answer. They are nullable so contextless retrievals keep working.
 */
public record RetrievalRequest(
    @NotNull UUID knowledgeBaseId,
    @NotBlank String query,
    @Min(1) int topK,
    Map<String, String> filters,
    UUID taskId,
    UUID attemptId
) {
}
