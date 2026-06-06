package ai.myrmec.engine.spi.retrieval;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * SPI for knowledge-base retrieval backends (vector search, BM25, hybrid, ...).
 *
 * <p>Engine-core ships a bundled RAGFlow-backed implementation for the
 * out-of-the-box experience; Enterprise / customer modules can register
 * alternative providers (external RAGFlow cluster, generic HTTP
 * embedding service, in-house vector store) by:</p>
 * <ol>
 *   <li>Implementing this interface as a Spring {@code @Component} that
 *       declares a unique provider id via {@link #id()}; and</li>
 *   <li>Setting the matching {@code knowledge_bases.provider_id} value
 *       on each KB you want routed to it.</li>
 * </ol>
 *
 * <p><strong>Threading:</strong> implementations MUST be thread-safe.
 * Engine invokes them on virtual-thread executors that may dispatch
 * many concurrent queries per process.</p>
 *
 * <p><strong>Failure semantics:</strong> implementations should throw
 * {@link RetrievalException} for any non-empty error. The engine
 * surfaces the failure to the agent as an empty result + warning rather
 * than aborting the task; agents may then decide to answer without RAG
 * grounding.</p>
 */
public interface RetrievalProvider {

    /**
     * Stable identifier matching {@code knowledge_bases.provider_id}. Must
     * be globally unique across the running engine; collisions cause a
     * startup-time validation failure.
     */
    String id();

    /**
     * Execute the retrieval query. Returns up to {@link RetrievalQuery#topK()}
     * hits in descending {@link Citation#score()} order. Empty list is a
     * valid response (no relevant chunks).
     */
    List<RetrievalResult> query(@Valid @NotNull RetrievalQuery query) throws RetrievalException;
}
