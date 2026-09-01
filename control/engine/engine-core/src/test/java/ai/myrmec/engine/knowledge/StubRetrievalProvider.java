// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

import ai.myrmec.engine.spi.retrieval.Citation;
import ai.myrmec.engine.spi.retrieval.RetrievalException;
import ai.myrmec.engine.spi.retrieval.RetrievalProvider;
import ai.myrmec.engine.spi.retrieval.RetrievalQuery;
import ai.myrmec.engine.spi.retrieval.RetrievalResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Deterministic in-memory retrieval provider for Tier 2 tests.
 *
 * <p>Performs simple keyword matching against chunks stored in
 * {@code knowledge_chunks}. Scores are binary (1.0 if the query string is
 * contained in the content, else 0.0). Results are stable and repeatable,
 * so tests can assert exact chunk IDs and source IDs.</p>
 */
@Slf4j
@Component
@Profile("e2e")
@RequiredArgsConstructor
public class StubRetrievalProvider implements RetrievalProvider {

    public static final String PROVIDER_ID = "stub";

    private final KnowledgeChunkRepository chunkRepository;

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public List<RetrievalResult> query(RetrievalQuery query) throws RetrievalException {
        String term = query.query().toLowerCase();
        List<RetrievalResult> results = chunkRepository.findAll().stream()
                .filter(c -> c.getKnowledgeSourceId() != null
                        && c.getKnowledgeSourceId().equals(query.knowledgeSourceId()))
                .filter(c -> c.getContent() != null
                        && c.getContent().toLowerCase().contains(term))
                .sorted(Comparator.comparing(KnowledgeChunk::getContent))
                .limit(query.topK())
                .map(c -> new RetrievalResult(
                        c.getContent(),
                        new Citation(
                                c.getId(),
                                query.knowledgeSourceId(),
                                resolveSourceName(c),
                                c.getLocator(),
                                1.0)))
                .toList();

        log.debug("StubRetrieval: source {} query '{}' → {} hits",
                query.knowledgeSourceId(), query.query(), results.size());

        return results;
    }

    private String resolveSourceName(KnowledgeChunk chunk) {
        return chunk.getMetadataJson() != null && !chunk.getMetadataJson().isBlank()
                ? chunk.getMetadataJson()
                : "Stub Source";
    }
}