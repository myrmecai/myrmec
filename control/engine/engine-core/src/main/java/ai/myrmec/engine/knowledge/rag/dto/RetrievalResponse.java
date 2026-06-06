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
}
