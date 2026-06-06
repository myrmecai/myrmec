package ai.myrmec.engine.spi.connector;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * SPI for knowledge-source connectors that fetch content from an external
 * system (git repo, S3/GCS/Azure bucket, web crawl, database schema)
 * and emit it as chunks for indexing.
 *
 * <p>Engine-core ships connectors for the bundled experience; Enterprise and
 * customer modules can register additional connectors by:</p>
 * <ol>
 *   <li>Implementing this interface as a Spring {@code @Component} with a
 *       unique {@link #type()}; and</li>
 *   <li>Setting the matching {@code knowledge_sources.connector_type} value
 *       on each source you want routed to it.</li>
 * </ol>
 *
 * <p><strong>Threading:</strong> implementations MUST be safe to invoke
 * concurrently from the engine's scheduled-sync executor. A single source
 * is never synced by more than one thread at a time (engine acquires a
 * row-level lock before dispatch).</p>
 *
 * <p><strong>Failure semantics:</strong> connectors should swallow
 * per-resource errors and surface them through {@link SyncResult#errors()}
 * so partial syncs still index the resources that succeeded. Throw
 * {@link ConnectorException} only for non-recoverable failures (auth
 * rejected, source unreachable, configuration invalid).</p>
 */
public interface KnowledgeSourceConnector {

    /**
     * Stable identifier matching {@code knowledge_sources.connector_type}.
     * Must be globally unique across the running engine; collisions cause a
     * startup-time validation failure. Examples: {@code "git"},
     * {@code "s3"}, {@code "azure-blob"}, {@code "web-crawl"},
     * {@code "db-schema"}.
     */
    String type();

    /**
     * Pull content from the source and emit chunks through
     * {@link ConnectorContext#chunkSink()}. Returns a non-null
     * {@link SyncResult} describing the outcome.
     *
     * @throws ConnectorException only for non-recoverable failures; see
     *         class docs for partial-success semantics.
     */
    SyncResult sync(@Valid @NotNull SourceLocator locator,
                    @NotNull ConnectorContext context) throws ConnectorException;
}
