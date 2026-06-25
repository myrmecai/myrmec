package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.secret.SecretResolverService;
import ai.myrmec.engine.spi.connector.ConnectorContext;
import ai.myrmec.engine.spi.connector.ConnectorException;
import ai.myrmec.engine.spi.connector.EmittedChunk;
import ai.myrmec.engine.spi.connector.KnowledgeSourceConnector;
import ai.myrmec.engine.spi.connector.SourceLocator;
import ai.myrmec.engine.spi.connector.SyncResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Resolves a {@link KnowledgeSource} to the {@link KnowledgeSourceConnector}
 * matching its {@code connectorType}, runs the sync, and persists every
 * emitted chunk + the resulting bookkeeping into {@code knowledge_sources}.
 *
 * <p>Connectors register themselves as Spring {@code @Component} beans;
 * collisions on {@code type()} fail boot. Secret resolution + the chunk
 * sink are provided through {@link ConnectorContext}; the secret resolver
 * delegates to {@link SecretResolverService}, scoped to the source's owning
 * project so a project source may use its own secrets or globals while a
 * SYSTEM/GROUP source may only use globals.</p>
 *
 * <p>Chunk persistence is upsert-by-locator: re-running a sync overwrites
 * the {@code content} + {@code metadata_json} for the same
 * {@code (knowledge_source_id, locator)} tuple rather than creating
 * duplicates. The {@code content_hash} column lets connectors short-circuit
 * a save when nothing changed (current MVP implementation just writes
 * every time — optimisation lands when the first real connector ships).</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ConnectorDispatcher implements InitializingBean {

    private final List<KnowledgeSourceConnector> connectors;
    private final KnowledgeSourceRepository knowledgeSourceRepository;
    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final SecretResolverService secretResolverService;

    private Map<String, KnowledgeSourceConnector> connectorsByType;

    @Override
    public void afterPropertiesSet() {
        Map<String, KnowledgeSourceConnector> byType = new HashMap<>();
        for (KnowledgeSourceConnector connector : connectors) {
            String type = connector.type();
            KnowledgeSourceConnector previous = byType.put(type, connector);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate KnowledgeSourceConnector type '" + type + "' between "
                                + previous.getClass().getName() + " and "
                                + connector.getClass().getName());
            }
        }
        this.connectorsByType = Map.copyOf(byType);
        log.info("ConnectorDispatcher initialised with {} connector(s): {}",
                connectorsByType.size(), connectorsByType.keySet());
    }

    /**
     * Run a synchronous sync for {@code sourceId}. Returns the
     * {@link SyncResult} from the connector after persisting bookkeeping.
     *
     * @throws ResourceNotFoundException if the source or its connector
     *         does not exist.
     * @throws ConnectorException        if the connector aborts.
     */
    @Transactional
    public SyncResult sync(UUID sourceId) throws ConnectorException {
        KnowledgeSource source = knowledgeSourceRepository.findById(sourceId)
                .orElseThrow(() -> new ResourceNotFoundException("KnowledgeSource", sourceId));
        KnowledgeSourceConnector connector = connectorsByType.get(source.getConnectorType());
        if (connector == null) {
            throw new ResourceNotFoundException("KnowledgeSourceConnector", source.getConnectorType());
        }

        SourceLocator locator = new SourceLocator(source.getUri(), parseConfig(source.getConfigJson()));
        Consumer<EmittedChunk> sink = chunk -> persistChunk(source.getId(), chunk);
        UUID projectId = resolveProjectScope(source.getKnowledgeBaseId());
        ConnectorContext ctx = new SimpleConnectorContext(
                source.getId(), projectId, secretResolverService, sink);

        SyncResult result;
        try {
            result = connector.sync(locator, ctx);
        } catch (ConnectorException e) {
            persistFailureBookkeeping(source);
            throw e;
        }
        persistResultBookkeeping(source, result);
        return result;
    }

    /**
     * Owning project of the knowledge base, or {@code null} for SYSTEM/GROUP
     * scope. Used to scope secret resolution: a project source may use its own
     * secrets or globals; a SYSTEM/GROUP source may only use globals.
     */
    private UUID resolveProjectScope(UUID knowledgeBaseId) {
        if (knowledgeBaseId == null) {
            return null;
        }
        return knowledgeBaseRepository.findById(knowledgeBaseId)
                .map(KnowledgeBase::getProjectId)
                .orElse(null);
    }

    private void persistChunk(UUID sourceId, EmittedChunk chunk) {
        Optional<KnowledgeChunk> existing =
                knowledgeChunkRepository.findByKnowledgeSourceIdAndLocator(sourceId, chunk.locator());
        KnowledgeChunk row = existing.orElseGet(KnowledgeChunk::new);
        row.setKnowledgeSourceId(sourceId);
        row.setLocator(chunk.locator());
        row.setContent(chunk.content());
        row.setContentHash(sha256Hex(chunk.content()));
        row.setMetadataJson(serialiseMetadata(chunk.metadata()));
        knowledgeChunkRepository.save(row);
    }

    private void persistResultBookkeeping(KnowledgeSource source, SyncResult result) {
        source.setLastSyncAt(result.completedAt());
        source.setLastSyncStatus(result.status().name());
        source.setLastSyncChunks(result.chunksEmitted());
        source.setLastSyncErrorCount(result.errors().size());
        source.setLastSyncDurationMs(result.duration().toMillis());
        knowledgeSourceRepository.save(source);
    }

    private void persistFailureBookkeeping(KnowledgeSource source) {
        source.setLastSyncAt(Instant.now());
        source.setLastSyncStatus(SyncResult.Status.FAILED.name());
        source.setLastSyncErrorCount(1);
        source.setLastSyncChunks(0L);
        source.setLastSyncDurationMs(0L);
        knowledgeSourceRepository.save(source);
    }

    private static Map<String, String> parseConfig(String configJson) {
        // MVP: configJson treated as opaque; connectors read the raw string from
        // their own context if needed. Real JSON parsing lands when the first
        // config-driven connector ships.
        return configJson == null ? Map.of() : Map.of("raw", configJson);
    }

    private static String serialiseMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey().replace("\"", "\\\"")).append("\":");
            sb.append("\"").append(entry.getValue().replace("\"", "\\\"")).append("\"");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Exposed for diagnostics + test assertions. */
    Map<String, KnowledgeSourceConnector> connectorsByType() {
        return connectorsByType;
    }

    /** Registered connector type ids (e.g. {@code git}, {@code manual}) for UI pickers. */
    public java.util.Set<String> connectorTypes() {
        return connectorsByType.keySet();
    }

    /**
     * Minimal {@link ConnectorContext} implementation. Secret tokens are
     * resolved against {@link SecretResolverService}, scoped to the source's
     * owning project ({@code null} = globals only). A token may be a secret
     * UUID or a secret name; an unresolved token yields {@code null}.
     */
    private record SimpleConnectorContext(UUID knowledgeSourceId,
                                          UUID projectId,
                                          SecretResolverService secretResolver,
                                          Consumer<EmittedChunk> chunkSink) implements ConnectorContext {

        @Override
        public String resolveSecret(String token) {
            if (token == null || token.isBlank()) {
                return null;
            }
            return secretResolver.resolveReferenceString(token, projectId).orElse(null);
        }
    }

    /** Helper used by tests to build a {@link SyncResult} with default duration. */
    public static SyncResult resultOf(SyncResult.Status status, long emitted, List<String> errors, Instant startedAt) {
        return new SyncResult(
                status,
                emitted,
                errors == null ? List.of() : new ArrayList<>(errors),
                Duration.between(startedAt, Instant.now()),
                Instant.now());
    }
}
