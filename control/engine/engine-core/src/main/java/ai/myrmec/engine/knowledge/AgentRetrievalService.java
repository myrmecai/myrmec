// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionContextAssembler;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.security.injection.UntrustedContentWrapper;
import ai.myrmec.engine.spi.retrieval.RetrievalException;
import ai.myrmec.engine.spi.retrieval.RetrievalProvider;
import ai.myrmec.engine.spi.retrieval.RetrievalQuery;
import ai.myrmec.engine.spi.retrieval.RetrievalResult;
import ai.myrmec.engine.spi.retrieval.Citation;
import ai.myrmec.engine.workflow.ExecutionEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service backing the {@code POST /api/v1/agent/retrieve} endpoint (§8).
 *
 * <p>Validates the requested {@code knowledgeSourceId} against the calling
 * session's pinned context, resolves the appropriate
 * {@link RetrievalProvider}, executes the query, wraps untrusted passages,
 * records the audit event, and returns hits to the agent.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRetrievalService {

    private final KnowledgeSourceRepository knowledgeSourceRepository;
    private final KnowledgeProviderVersionRepository providerVersionRepository;
    private final SessionRepository sessionRepository;
    private final SessionContextAssembler sessionContextAssembler;
    private final UntrustedContentWrapper untrustedContentWrapper;
    private final ExecutionEventService executionEventService;
    private final RetrievalDispatcher retrievalDispatcher;

    /**
     * Execute a retrieval query.
     *
     * @param agentInstanceId  the authenticated agent instance
     * @param request          the retrieval request
     * @return list of hits (empty on provider failure — agent degrades gracefully)
     */
    @Transactional
    public List<RetrievalHit> retrieve(UUID agentInstanceId, RetrievalRequest request) {
        // 1. Resolve the session — prefer explicit sessionId, else find active
        //    session for this agent instance.
        Session session = resolveSession(agentInstanceId, request.sessionId());
        if (session == null) {
            log.warn("Retrieval rejected — no active session for agent {} (sessionId={})",
                    agentInstanceId, request.sessionId());
            throw new RetrievalForbiddenException("No active session for this agent.");
        }

        // 2. Validate the knowledge source is pinned to this session
        if (!sessionContextAssembler.isKnowledgeSourcePinned(session.getId(),
                request.knowledgeSourceId())) {
            log.warn("Retrieval rejected — source {} not pinned to session {} (agent {})",
                    request.knowledgeSourceId(), session.getId(), agentInstanceId);
            throw new RetrievalForbiddenException(
                    "Knowledge source " + request.knowledgeSourceId()
                            + " is not pinned to this session.");
        }

        // 3. Resolve the knowledge source → provider version → provider
        KnowledgeSource source = knowledgeSourceRepository.findById(request.knowledgeSourceId())
                .orElse(null);
        if (source == null) {
            log.warn("Retrieval — knowledge source {} not found", request.knowledgeSourceId());
            return List.of();
        }

        KnowledgeProviderVersion providerVersion = providerVersionRepository
                .findById(source.getProviderVersionId()).orElse(null);
        if (providerVersion == null) {
            log.warn("Retrieval — provider version {} not found for source {}",
                    source.getProviderVersionId(), request.knowledgeSourceId());
            return List.of();
        }

        // Resolve provider via the dispatcher.  This call is deliberately
        // placed BEFORE the try/catch below so that a configuration error
        // (unknown provider id) surfaces as a 404 rather than being swallowed
        // into an empty result list.
        String providerId = resolveProviderId(providerVersion);
        RetrievalProvider provider = retrievalDispatcher.resolve(providerId);
        if (provider == null) {
            log.warn("Retrieval — no provider registered for id '{}'", providerVersion.getProviderId());
            return List.of();
        }

        // 4. Build the SPI query
        int topK = request.topK() != null ? request.topK() : 5;
        Map<String, String> filters = request.filters() != null ? request.filters() : Map.of();
        RetrievalQuery query = new RetrievalQuery(
                request.knowledgeSourceId(),
                request.query(),
                topK,
                filters);

        // 5. Execute the query
        List<RetrievalResult> results;
        try {
            results = provider.query(query);
        } catch (RetrievalException e) {
            log.warn("Retrieval provider '{}' failed for source {}: {}",
                    provider.id(), request.knowledgeSourceId(), e.getMessage());
            return List.of();  // D4: provider failure → empty result, not an error
        }

        // 6. Wrap passages + build response
        List<RetrievalHit> hits = new ArrayList<>();
        List<UUID> chunkIds = new ArrayList<>();
        List<UUID> sourceIds = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (RetrievalResult result : results) {
            Citation c = result.citation();
            String wrappedPassage = untrustedContentWrapper.wrapRetrieval(
                    result.passage(), c.sourceName());
            hits.add(new RetrievalHit(
                    wrappedPassage,
                    c.chunkId(),
                    c.sourceId(),
                    c.sourceName(),
                    c.locator(),
                    c.score()));
            chunkIds.add(c.chunkId());
            sourceIds.add(c.sourceId());
            scores.add(c.score());
        }

        // 7. Record audit event
        try {
            executionEventService.recordRetrieval(
                    request.taskId(),
                    request.attemptId(),
                    request.knowledgeSourceId(),
                    request.query(),
                    topK,
                    chunkIds,
                    sourceIds,
                    scores);
        } catch (Exception e) {
            log.warn("Failed to record retrieval audit event: {}", e.getMessage());
        }

        log.info("Retrieval for agent {} session {} source {} → {} hits",
                agentInstanceId, session.getId(), request.knowledgeSourceId(), hits.size());
        return hits;
    }

    private Session resolveSession(UUID agentInstanceId, UUID explicitSessionId) {
        if (explicitSessionId != null) {
            return sessionRepository.findByIdAndStatus(explicitSessionId, "ACTIVE")
                    .orElse(null);
        }
        // Without an explicit sessionId we cannot reliably resolve the session
        // from the agent instance alone (an agent may have multiple concurrent
        // sessions). The caller should pass sessionId; if not, we reject.
        log.debug("No explicit sessionId in retrieval request from agent {} — cannot resolve",
                agentInstanceId);
        return null;
    }

    /**
     * Resolve the SPI provider id from the provider version's config map.
     * Today the string id is stored in {@code version.config["providerId"]};
     * if absent we default to {@code "http-retrieval"} so the existing
     * {@link HttpRetrievalProvider} keeps working.
     */
    private String resolveProviderId(KnowledgeProviderVersion version) {
        Map<String, Object> config = version.getConfig();
        if (config != null && config.get("providerId") instanceof String id && !id.isBlank()) {
            return id;
        }
        return HttpRetrievalProvider.PROVIDER_ID;
    }
}