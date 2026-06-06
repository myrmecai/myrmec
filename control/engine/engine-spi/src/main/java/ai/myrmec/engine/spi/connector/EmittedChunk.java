package ai.myrmec.engine.spi.connector;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Collections;
import java.util.Map;

/**
 * Single content chunk emitted by a {@link KnowledgeSourceConnector} during sync.
 *
 * <p>The connector is responsible for chunking strategy (file-per-chunk,
 * fixed-size windows, semantic splits, ...). Engine writes each emission
 * verbatim to {@code knowledge_chunks}; embeddings are computed by the
 * retrieval provider asynchronously after persistence.</p>
 *
 * @param locator   non-blank source-relative locator the user clicks through
 *                  to (e.g. {@code docs/architecture.md#L42}). Forwarded to
 *                  {@link ai.myrmec.engine.spi.retrieval.Citation#locator()}.
 * @param content   non-blank chunk text. Length should fit comfortably inside
 *                  the largest expected model context window.
 * @param metadata  non-null free-form metadata persisted alongside the chunk
 *                  (file extension, language, last commit, ...). Engine
 *                  serialises to JSONB; values must be JSON-encodable strings.
 *                  Use empty map for "no metadata".
 */
public record EmittedChunk(
    @NotBlank String locator,
    @NotBlank String content,
    @NotNull Map<String, String> metadata
) {
    public EmittedChunk {
        metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(metadata);
    }
}
