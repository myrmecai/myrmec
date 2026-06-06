package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.spi.retrieval.RetrievalException;
import ai.myrmec.engine.spi.retrieval.RetrievalQuery;
import ai.myrmec.engine.spi.retrieval.RetrievalResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test proving the full Phase 5 RAG chain wires up cleanly:
 * KnowledgeBaseService persists KB + source, chunks repo accepts hand-written
 * rows, and {@link RetrievalDispatcher} routes the query through the
 * {@link StubRetrievalProvider} to produce a citation pointing back at the
 * stored chunk.
 *
 * <p>Doubles as a regression check that the {@code stub} provider id stays
 * unique — duplicate provider beans would fail the dispatcher's startup
 * check long before this test runs.</p>
 */
@Transactional
class RetrievalDispatcherTest extends IntegrationTestBase {

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private KnowledgeChunkRepository knowledgeChunkRepository;

    @Autowired
    private RetrievalDispatcher dispatcher;

    @Test
    void stubProviderReturnsKeywordMatchingChunk() throws RetrievalException {
        // Create a system-scoped KB routed to the stub provider.
        KnowledgeBase kb = knowledgeBaseService.createSystemBase(
                "retrieval-it-kb-" + UUID.randomUUID().toString().substring(0, 8),
                "Built by RetrievalDispatcherIT",
                StubRetrievalProvider.PROVIDER_ID,
                null);

        // Add one source under the KB.
        KnowledgeSource source = knowledgeBaseService.addSource(
                kb.getId(),
                "manual",
                "docs",
                "memory://docs",
                null,
                null);

        // Seed two chunks: one matches the query, one doesn't.
        KnowledgeChunk match = new KnowledgeChunk();
        match.setKnowledgeSourceId(source.getId());
        match.setLocator("docs/architecture.md#L10");
        match.setContent("The control engine is a Spring Boot application that dispatches tasks to agents.");
        match.setContentHash("hash-architecture");
        knowledgeChunkRepository.save(match);

        KnowledgeChunk noise = new KnowledgeChunk();
        noise.setKnowledgeSourceId(source.getId());
        noise.setLocator("docs/changelog.md#L1");
        noise.setContent("Unrelated release notes about UI tweaks.");
        noise.setContentHash("hash-changelog");
        knowledgeChunkRepository.save(noise);

        // Dispatch a query that should hit the architecture chunk but not the changelog.
        RetrievalQuery query = new RetrievalQuery(
                kb.getId(),
                "control engine spring",
                5,
                Map.of());
        var results = dispatcher.dispatch(query);

        assertThat(results).hasSize(1);
        RetrievalResult result = results.get(0);
        assertThat(result.passage()).contains("control engine");
        assertThat(result.citation().chunkId()).isEqualTo(match.getId());
        assertThat(result.citation().sourceId()).isEqualTo(source.getId());
        assertThat(result.citation().sourceName()).isEqualTo("docs");
        assertThat(result.citation().locator()).isEqualTo("docs/architecture.md#L10");
        assertThat(result.citation().score()).isGreaterThan(0.0);
    }

    @Test
    void dispatcherRegistersStubProvider() {
        assertThat(dispatcher.providersById()).containsKey(StubRetrievalProvider.PROVIDER_ID);
    }
}
