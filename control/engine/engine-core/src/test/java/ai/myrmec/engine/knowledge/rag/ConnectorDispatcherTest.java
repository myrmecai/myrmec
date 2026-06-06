package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.spi.connector.ConnectorException;
import ai.myrmec.engine.spi.connector.EmittedChunk;
import ai.myrmec.engine.spi.connector.SyncResult;
import ai.myrmec.engine.spi.retrieval.RetrievalException;
import ai.myrmec.engine.spi.retrieval.RetrievalQuery;
import ai.myrmec.engine.spi.retrieval.RetrievalResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the full Phase 5c connector pipeline:
 * stage chunks → ConnectorDispatcher.sync() → ManualConnector emits →
 * dispatcher persists into {@code knowledge_chunks} + updates bookkeeping →
 * RetrievalDispatcher → StubRetrievalProvider returns them.
 *
 * <p>End-to-end through every layer except the agent SDK (Phase 5d) and a
 * real external connector (deferred). Locks in the contract that future
 * git/S3/web connectors will need to honour.</p>
 */
@Transactional
class ConnectorDispatcherTest extends IntegrationTestBase {

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private KnowledgeSourceRepository knowledgeSourceRepository;

    @Autowired
    private KnowledgeChunkRepository knowledgeChunkRepository;

    @Autowired
    private ManualConnector manualConnector;

    @Autowired
    private ConnectorDispatcher connectorDispatcher;

    @Autowired
    private RetrievalDispatcher retrievalDispatcher;

    @Test
    void manualConnectorSyncPersistsChunksAndUpdatesBookkeeping() throws ConnectorException {
        KnowledgeBase kb = knowledgeBaseService.createSystemBase(
                "connector-it-kb-" + UUID.randomUUID().toString().substring(0, 8),
                "Built by ConnectorDispatcherTest",
                StubRetrievalProvider.PROVIDER_ID,
                null);
        KnowledgeSource source = knowledgeBaseService.addSource(
                kb.getId(),
                ManualConnector.CONNECTOR_TYPE,
                "docs",
                "memory://docs",
                null,
                null);

        manualConnector.stage(source.getId(), List.of(
                new EmittedChunk("docs/intro.md#L1", "Welcome to the Myrmec control engine.", Map.of("section", "intro")),
                new EmittedChunk("docs/api.md#L42", "Agents register via POST /api/v1/agent/auth/register.", Map.of("section", "api"))
        ));

        SyncResult result = connectorDispatcher.sync(source.getId());

        assertThat(result.status()).isEqualTo(SyncResult.Status.SUCCESS);
        assertThat(result.chunksEmitted()).isEqualTo(2);
        assertThat(result.errors()).isEmpty();

        // Chunks persisted.
        List<KnowledgeChunk> persisted = knowledgeChunkRepository.findByKnowledgeSourceId(source.getId());
        assertThat(persisted).hasSize(2);
        assertThat(persisted).allSatisfy(chunk -> {
            assertThat(chunk.getContentHash()).hasSize(64);
            assertThat(chunk.getContent()).isNotBlank();
        });

        // Bookkeeping updated on the source row.
        KnowledgeSource reloaded = knowledgeSourceRepository.findById(source.getId()).orElseThrow();
        assertThat(reloaded.getLastSyncStatus()).isEqualTo("SUCCESS");
        assertThat(reloaded.getLastSyncChunks()).isEqualTo(2L);
        assertThat(reloaded.getLastSyncErrorCount()).isZero();
        assertThat(reloaded.getLastSyncAt()).isNotNull();
    }

    @Test
    void resyncOfSameLocatorOverwritesInsteadOfDuplicating() throws ConnectorException {
        KnowledgeBase kb = knowledgeBaseService.createSystemBase(
                "resync-it-kb-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                StubRetrievalProvider.PROVIDER_ID,
                null);
        KnowledgeSource source = knowledgeBaseService.addSource(
                kb.getId(),
                ManualConnector.CONNECTOR_TYPE,
                "docs",
                "memory://docs",
                null,
                null);

        // First sync: one chunk with original content.
        manualConnector.stage(source.getId(),
                "docs/readme.md#L1",
                "Original content of the readme.",
                Map.of());
        connectorDispatcher.sync(source.getId());

        // Second sync: same locator, different content.
        manualConnector.stage(source.getId(),
                "docs/readme.md#L1",
                "Updated content of the readme.",
                Map.of());
        connectorDispatcher.sync(source.getId());

        // Expect exactly one row, with the new content.
        List<KnowledgeChunk> persisted = knowledgeChunkRepository.findByKnowledgeSourceId(source.getId());
        assertThat(persisted).hasSize(1);
        assertThat(persisted.get(0).getContent()).isEqualTo("Updated content of the readme.");
    }

    @Test
    void chunksFromSyncAreQueryableThroughRetrievalDispatcher() throws ConnectorException, RetrievalException {
        KnowledgeBase kb = knowledgeBaseService.createSystemBase(
                "chain-it-kb-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                StubRetrievalProvider.PROVIDER_ID,
                null);
        KnowledgeSource source = knowledgeBaseService.addSource(
                kb.getId(),
                ManualConnector.CONNECTOR_TYPE,
                "docs",
                "memory://docs",
                null,
                null);

        manualConnector.stage(source.getId(),
                "docs/auth.md#L10",
                "JWT tokens are issued by the auth controller.",
                Map.of());
        connectorDispatcher.sync(source.getId());

        List<RetrievalResult> hits = retrievalDispatcher.dispatch(
                new RetrievalQuery(kb.getId(), "JWT tokens auth", 5, Map.of()));

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).passage()).contains("JWT tokens");
        assertThat(hits.get(0).citation().locator()).isEqualTo("docs/auth.md#L10");
    }

    @Test
    void dispatcherRegistersManualConnector() {
        assertThat(connectorDispatcher.connectorsByType())
                .containsKey(ManualConnector.CONNECTOR_TYPE);
    }
}
