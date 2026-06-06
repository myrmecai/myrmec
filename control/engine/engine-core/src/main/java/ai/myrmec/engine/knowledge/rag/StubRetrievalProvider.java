package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine.spi.retrieval.Citation;
import ai.myrmec.engine.spi.retrieval.RetrievalException;
import ai.myrmec.engine.spi.retrieval.RetrievalProvider;
import ai.myrmec.engine.spi.retrieval.RetrievalQuery;
import ai.myrmec.engine.spi.retrieval.RetrievalResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * In-memory keyword-matching {@link RetrievalProvider} used as the bundled
 * fallback when no vector store is configured. Loads chunks for the queried
 * KB's sources directly from {@code knowledge_chunks}, scores them by
 * count of matching query tokens in {@link KnowledgeChunk#getContent()}, and
 * returns the top-k.
 *
 * <p>Not a real RAG implementation — designed to be:</p>
 * <ul>
 *   <li>Sufficient for E2E tests so the framework can validate the full
 *       agent → engine → provider → UI citation flow without standing up
 *       a Ragflow sidecar.</li>
 *   <li>The default for fresh installs so the platform is usable out of
 *       the box; users wire in a real provider when they add real
 *       knowledge bases.</li>
 * </ul>
 *
 * <p>Provider id is {@code "stub"}. Use it explicitly in
 * {@code knowledge_bases.provider_id} to route a KB through this provider.</p>
 */
@Component
@RequiredArgsConstructor
public class StubRetrievalProvider implements RetrievalProvider {

    public static final String PROVIDER_ID = "stub";

    private final KnowledgeSourceRepository knowledgeSourceRepository;
    private final KnowledgeChunkRepository knowledgeChunkRepository;

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public List<RetrievalResult> query(RetrievalQuery query) throws RetrievalException {
        String[] tokens = query.query().toLowerCase().split("\\s+");
        List<KnowledgeSource> sources = knowledgeSourceRepository.findByKnowledgeBaseId(query.knowledgeBaseId());
        List<Scored> scored = new ArrayList<>();
        for (KnowledgeSource source : sources) {
            for (KnowledgeChunk chunk : knowledgeChunkRepository.findByKnowledgeSourceId(source.getId())) {
                String content = chunk.getContent().toLowerCase();
                int hits = 0;
                for (String token : tokens) {
                    if (!token.isBlank() && content.contains(token)) {
                        hits++;
                    }
                }
                if (hits > 0) {
                    double normalized = (double) hits / Math.max(tokens.length, 1);
                    scored.add(new Scored(source, chunk, normalized));
                }
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<RetrievalResult> results = new ArrayList<>(query.topK());
        for (int i = 0; i < scored.size() && i < query.topK(); i++) {
            Scored hit = scored.get(i);
            Citation citation = new Citation(
                    hit.chunk.getId(),
                    hit.source.getId(),
                    hit.source.getName(),
                    hit.chunk.getLocator(),
                    hit.score
            );
            results.add(new RetrievalResult(hit.chunk.getContent(), citation));
        }
        return results;
    }

    private record Scored(KnowledgeSource source, KnowledgeChunk chunk, double score) {}
}
