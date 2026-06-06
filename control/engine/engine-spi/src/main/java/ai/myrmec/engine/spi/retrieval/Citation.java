package ai.myrmec.engine.spi.retrieval;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Per-hit citation metadata returned alongside a retrieved passage.
 *
 * <p>The engine forwards citations verbatim to the agent and the UI so users
 * can click through to source material. Providers MUST populate every
 * non-null field on every citation; the {@code sourceId} ties the citation
 * back to the {@code knowledge_sources} row that produced the chunk.</p>
 *
 * @param chunkId    non-null primary key of the underlying
 *                   {@code knowledge_chunks} row, used by the UI side panel
 *                   to fetch surrounding context on demand.
 * @param sourceId   non-null primary key of the {@code knowledge_sources}
 *                   row the chunk came from.
 * @param sourceName non-blank human-readable source name suitable for chip
 *                   labels (e.g. "github.com/acme/api · main").
 * @param locator    non-blank deep-link / file path / row identifier the
 *                   user can use to open the original (e.g.
 *                   {@code docs/architecture.md#L42}). Providers should
 *                   prefer URLs when the source supports them.
 * @param score      similarity / rank score in [0.0, 1.0]; higher is more
 *                   relevant. Providers that don't expose scores should
 *                   return {@code 0.0} for ties.
 */
public record Citation(
    @NotNull UUID chunkId,
    @NotNull UUID sourceId,
    @NotBlank String sourceName,
    @NotBlank String locator,
    double score
) {
}
