package ai.myrmec.engine.spi.connector;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Per-sync context handed to a {@link KnowledgeSourceConnector#sync} call.
 *
 * <p>Provides the connector with:</p>
 * <ul>
 *   <li>{@link #knowledgeSourceId()} — the {@code knowledge_sources} row
 *       being synced, used as the foreign key on every emitted chunk.</li>
 *   <li>{@link #secretResolver()} — opaque token → plaintext lookup for
 *       authentication material (PAT, bucket creds). Connectors MUST NOT
 *       persist resolved secrets; pass them straight to the underlying
 *       client. Returns {@code null} if the token doesn't resolve.</li>
 *   <li>{@link #chunkSink()} — call once per emitted chunk. Engine batches
 *       and writes to {@code knowledge_chunks}. Throwing from the sink
 *       indicates a non-recoverable persistence error and aborts the sync.
 *       Used when the retrieval provider does NOT support ingestion (e.g.
 *       {@code builtin}, {@code http}).</li>
 *   <li>{@link #fileSink()} — call once per emitted raw file. Engine
 *       forwards each file to the retrieval provider's {@code ingest()}
 *       pipeline (e.g. RAGFlow upload + parse). Used when the retrieval
 *       provider DOES support ingestion. May be {@code null} when the
 *       provider doesn't support ingestion; connectors should check
 *       before calling.</li>
 * </ul>
 *
 * <p>The context is request-scoped; do not retain it past the {@code sync}
 * call.</p>
 */
public interface ConnectorContext {

    /** Non-null id of the {@code knowledge_sources} row being synced. */
    @NotNull UUID knowledgeSourceId();

    /**
     * Resolve a secret token (e.g. {@code "github-pat"}) to its plaintext
     * value. Returns {@code null} when the token is not registered for
     * this source's owning project/group/system scope.
     */
    String resolveSecret(@NotNull String token);

    /**
     * Sink for emitted chunks. Used when the retrieval provider does NOT
     * support ingestion (e.g. {@code builtin}, {@code http}); the engine
     * persists each chunk to {@code knowledge_chunks}.
     */
    @NotNull Consumer<EmittedChunk> chunkSink();

    /**
     * Sink for emitted raw files. Used when the retrieval provider DOES
     * support ingestion (e.g. {@code ragflow}); the engine forwards each
     * file to {@link ai.myrmec.engine.spi.retrieval.RetrievalProvider#ingest}.
     * Returns {@code null} when the provider doesn't support ingestion;
     * connectors should check before calling.
     */
    Consumer<EmittedFile> fileSink();
}
