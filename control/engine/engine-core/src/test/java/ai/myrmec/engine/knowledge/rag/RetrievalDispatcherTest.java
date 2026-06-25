package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.spi.retrieval.RetrievalException;
import ai.myrmec.engine.spi.retrieval.RetrievalQuery;
import ai.myrmec.engine.spi.retrieval.RetrievalResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void dispatcherRegistersRagflowProvider() {
        assertThat(dispatcher.providersById())
                .containsKey(RagflowRetrievalProvider.PROVIDER_ID);
        assertThat(dispatcher.providersById().get(RagflowRetrievalProvider.PROVIDER_ID))
                .isInstanceOf(RagflowRetrievalProvider.class);
    }

    @Test
    void ragflowKbRoutesToRagflowProvider() {
        // A KB pinned to provider_id=ragflow must reach the ragflow bean. With no
        // resolvable secret in the test DB the provider fails fast with a
        // RetrievalException (proving it routed there, not a 404 from the dispatcher).
        KnowledgeBase kb = knowledgeBaseService.createSystemBase(
                "ragflow-route-kb-" + UUID.randomUUID().toString().substring(0, 8),
                "Routed to ragflow",
                RagflowRetrievalProvider.PROVIDER_ID,
                """
                {
                  "baseUrl": "http://127.0.0.1:1",
                  "datasetId": "ds-1",
                  "apiKeySecretRef": "missing-secret"
                }""");

        RetrievalQuery query = new RetrievalQuery(kb.getId(), "anything", 5, Map.of());
        assertThatThrownBy(() -> dispatcher.dispatch(query))
                .isInstanceOf(RetrievalException.class);
    }

    @Test
    void dispatcherRegistersHttpProvider() {
        assertThat(dispatcher.providersById())
                .containsKey(HttpRetrievalProvider.PROVIDER_ID);
        assertThat(dispatcher.providersById().get(HttpRetrievalProvider.PROVIDER_ID))
                .isInstanceOf(HttpRetrievalProvider.class);
    }

    @Test
    void httpKbRoutesToHttpProvider() {
        // A KB pinned to provider_id=http must reach the http bean. With no
        // resolvable secret in the test DB the provider fails fast with a
        // RetrievalException (proving it routed there, not a 404 from the dispatcher).
        KnowledgeBase kb = knowledgeBaseService.createSystemBase(
                "http-route-kb-" + UUID.randomUUID().toString().substring(0, 8),
                "Routed to http",
                HttpRetrievalProvider.PROVIDER_ID,
                """
                {
                  "endpoint": "http://127.0.0.1:1/search",
                  "authSecretRef": "missing-secret",
                  "responseMapping": {
                    "hitsPath": "$.results",
                    "passagePath": "$.text",
                    "sourceNamePath": "$.source",
                    "locatorPath": "$.url"
                  }
                }""");

        RetrievalQuery query = new RetrievalQuery(kb.getId(), "anything", 5, Map.of());
        assertThatThrownBy(() -> dispatcher.dispatch(query))
                .isInstanceOf(RetrievalException.class);
    }

    @Test
    void unknownProviderIdIsNotFound() {
        KnowledgeBase kb = knowledgeBaseService.createSystemBase(
                "unknown-provider-kb-" + UUID.randomUUID().toString().substring(0, 8),
                "Routed to a non-existent provider",
                "does-not-exist",
                null);

        RetrievalQuery query = new RetrievalQuery(kb.getId(), "anything", 5, Map.of());
        assertThatThrownBy(() -> dispatcher.dispatch(query))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
