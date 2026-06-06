package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.spi.retrieval.RetrievalException;
import ai.myrmec.engine.spi.retrieval.RetrievalProvider;
import ai.myrmec.engine.spi.retrieval.RetrievalQuery;
import ai.myrmec.engine.spi.retrieval.RetrievalResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves a {@link RetrievalQuery} to the {@link RetrievalProvider} bound to
 * the target {@link KnowledgeBase} and dispatches the call. One bean per
 * provider id is required at startup; collisions throw at boot.
 *
 * <p>Acts as the single integration point for {@code ctx.retrieve()} (Phase 5d)
 * and the agent task context resolution path. ACL enforcement is the caller's
 * responsibility — this class assumes the KB id has already been resolved
 * against the user/project scope.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RetrievalDispatcher implements InitializingBean {

    private final List<RetrievalProvider> providers;
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    private Map<String, RetrievalProvider> providersById;

    @Override
    public void afterPropertiesSet() {
        Map<String, RetrievalProvider> byId = new HashMap<>();
        for (RetrievalProvider provider : providers) {
            String id = provider.id();
            RetrievalProvider previous = byId.put(id, provider);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate RetrievalProvider id '" + id + "' between "
                                + previous.getClass().getName() + " and "
                                + provider.getClass().getName());
            }
        }
        this.providersById = Map.copyOf(byId);
        log.info("RetrievalDispatcher initialised with {} provider(s): {}",
                providersById.size(), providersById.keySet());
    }

    /**
     * Dispatch the query to the provider bound to its target KB.
     * @throws ResourceNotFoundException if the KB or its provider does not exist.
     * @throws RetrievalException if the provider fails.
     */
    public List<RetrievalResult> dispatch(RetrievalQuery query) throws RetrievalException {
        UUID kbId = query.knowledgeBaseId();
        KnowledgeBase kb = knowledgeBaseRepository.findById(kbId)
                .orElseThrow(() -> new ResourceNotFoundException("KnowledgeBase", kbId));
        RetrievalProvider provider = providersById.get(kb.getProviderId());
        if (provider == null) {
            throw new ResourceNotFoundException("RetrievalProvider", kb.getProviderId());
        }
        return provider.query(query);
    }

    /** Exposed for diagnostics + test assertions. */
    Map<String, RetrievalProvider> providersById() {
        return providersById;
    }
}
