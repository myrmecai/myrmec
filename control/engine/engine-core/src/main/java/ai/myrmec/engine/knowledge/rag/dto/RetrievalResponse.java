package ai.myrmec.engine.knowledge.rag.dto;

import ai.myrmec.engine.spi.retrieval.Citation;
import ai.myrmec.engine.spi.retrieval.RetrievalResult;

import java.util.UUID;

/**
 * Wire representation of one retrieval hit returned by
 * {@code POST /api/v1/agent/retrieve}.
 */
public record RetrievalResponse(
    String passage,
    UUID chunkId,
    UUID sourceId,
    String sourceName,
    String locator,
    double score
) {

    public static RetrievalResponse from(RetrievalResult result) {
        Citation c = result.citation();
        return new RetrievalResponse(
                result.passage(),
                c.chunkId(),
                c.sourceId(),
                c.sourceName(),
                c.locator(),
                c.score());
    }

    /**
     * Phase 9f — variant that wraps the passage in an
     * {@code <untrusted>} envelope before returning it to the agent.
     * Used by {@link ai.myrmec.engine.knowledge.rag.AgentRetrievalController}
     * on the wire path; the unwrapped variant remains for internal
     * callers that don't feed the model directly.
     */
    public static RetrievalResponse from(RetrievalResult result,
            ai.myrmec.engine.security.injection.UntrustedContentWrapper wrapper) {
        Citation c = result.citation();
        String wrapped = wrapper == null
                ? result.passage()
                : wrapper.wrapRetrieval(result.passage(), c.sourceName());
        return new RetrievalResponse(
                wrapped,
                c.chunkId(),
                c.sourceId(),
                c.sourceName(),
                c.locator(),
                c.score());
    }
}
