package ai.myrmec.engine.spi.retrieval;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * A single retrieval hit returned by a {@link RetrievalProvider}.
 *
 * <p>{@code passage} is the chunk text the agent will see in its context
 * window. {@code citation} is forwarded to the UI so the user can click
 * through. The pair travels together end-to-end so the engine can enforce
 * citation discipline (no passage in the answer without a corresponding
 * citation).</p>
 *
 * @param passage  non-blank chunk content as stored in {@code knowledge_chunks}.
 *                 Length should fit comfortably inside the model context
 *                 window; chunking strategy is the provider's responsibility.
 * @param citation non-null metadata referencing the source row + locator.
 */
public record RetrievalResult(
    @NotBlank String passage,
    @NotNull Citation citation
) {
}
