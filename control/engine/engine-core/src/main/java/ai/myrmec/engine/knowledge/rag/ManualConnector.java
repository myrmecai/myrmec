package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine.spi.connector.ConnectorContext;
import ai.myrmec.engine.spi.connector.ConnectorException;
import ai.myrmec.engine.spi.connector.EmittedChunk;
import ai.myrmec.engine.spi.connector.KnowledgeSourceConnector;
import ai.myrmec.engine.spi.connector.SourceLocator;
import ai.myrmec.engine.spi.connector.SyncResult;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link KnowledgeSourceConnector} (type = {@code "manual"}) that
 * emits chunks from a programmatically-staged list keyed by source id.
 *
 * <p>Two use cases:</p>
 * <ul>
 *   <li><strong>Tests:</strong> stage chunks via {@link #stage(UUID, List)}
 *       before calling {@code ConnectorDispatcher.sync(sourceId)} and assert
 *       the dispatcher pipeline persists them correctly.</li>
 *   <li><strong>Fresh installs:</strong> create a "manual" source on a KB and
 *       seed chunks through a (future) admin REST endpoint. Lets operators
 *       experiment with RAG before standing up git / S3 / web connectors.</li>
 * </ul>
 *
 * <p>Not intended for production knowledge sources at scale — chunk staging
 * is process-local and lost on restart. Provided as the bundled
 * "always works" connector so the rest of the RAG pipeline can be exercised
 * end-to-end without external dependencies.</p>
 */
@Component
public class ManualConnector implements KnowledgeSourceConnector {

    public static final String CONNECTOR_TYPE = "manual";

    /** Source id → staged chunks. Cleared once consumed. */
    private final ConcurrentHashMap<UUID, List<EmittedChunk>> stagedBySource = new ConcurrentHashMap<>();

    @Override
    public String type() {
        return CONNECTOR_TYPE;
    }

    /**
     * Stage chunks for the next {@link #sync} call against {@code sourceId}.
     * Replaces any previously staged batch for the same source.
     */
    public void stage(UUID sourceId, List<EmittedChunk> chunks) {
        stagedBySource.put(sourceId, new ArrayList<>(chunks));
    }

    /** Convenience for tests: stage a single chunk. */
    public void stage(UUID sourceId, String locator, String content, Map<String, String> metadata) {
        stage(sourceId, List.of(new EmittedChunk(locator, content, metadata)));
    }

    @Override
    public SyncResult sync(SourceLocator locator, ConnectorContext context) throws ConnectorException {
        Instant startedAt = Instant.now();
        List<EmittedChunk> chunks = stagedBySource.remove(context.knowledgeSourceId());
        long emitted = 0;
        if (chunks != null) {
            for (EmittedChunk chunk : chunks) {
                context.chunkSink().accept(chunk);
                emitted++;
            }
        }
        return new SyncResult(
                SyncResult.Status.SUCCESS,
                emitted,
                List.of(),
                Duration.between(startedAt, Instant.now()),
                Instant.now());
    }
}
