package ai.myrmec.engine.spi.retrieval;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

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

    /**
     * Whether this provider supports ingestion (uploading content via
     * connectors). Providers that manage their own content (e.g. {@code http}
     * BYO-RAG) return {@code false}; providers that accept uploaded files
     * (e.g. {@code ragflow}) return {@code true}.
     *
     * <p>When {@code false}, the engine blocks KB source creation for this
     * provider — sources are meaningless without an ingestion-capable
     * backend.</p>
     */
    default boolean supportsIngestion() {
        return false;
    }

    /**
     * Ingest a file into the provider's index. Called by
     * {@code ConnectorDispatcher} after a connector emits a file during sync.
     *
     * <p>Implementations should upload the file to the provider's ingestion
     * API and trigger parsing/indexing. The default implementation throws
     * {@link UnsupportedOperationException} — providers that return
     * {@code true} for {@link #supportsIngestion()} must override this.</p>
     *
     * @param kbId            the knowledge base ID (for config lookup)
     * @param providerConfig  the KB's {@code provider_config} JSON string
     * @param projectId       the KB's owning project ID (for secret resolution;
     *                        null for SYSTEM/GROUP scope)
     * @param file            the file to ingest
     * @throws RetrievalException if ingestion fails
     */
    default void ingest(UUID kbId, String providerConfig, UUID projectId,
                        ai.myrmec.engine.spi.connector.EmittedFile file) throws RetrievalException {
        throw new UnsupportedOperationException("Provider " + id() + " does not support ingestion");
    }
}
