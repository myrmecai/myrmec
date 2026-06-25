package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine._system.security.AgentPrincipal;
import ai.myrmec.engine.knowledge.rag.dto.RetrievalRequest;
import ai.myrmec.engine.knowledge.rag.dto.RetrievalResponse;
import ai.myrmec.engine.spi.retrieval.RetrievalException;
import ai.myrmec.engine.spi.retrieval.RetrievalQuery;
import ai.myrmec.engine.spi.retrieval.RetrievalResult;
import ai.myrmec.engine.workflow.ExecutionEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Agent-facing retrieval endpoint. Backs {@code ctx.retrieve()} in the
 * Python SDK. Requires the {@code AGENT} role (gated by SecurityConfig
 * via {@code /api/v1/agent/**}).
 */
@RestController
@RequestMapping("/api/v1/agent/retrieve")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Agent RAG", description = "Retrieval-augmented generation endpoints for agents")
public class AgentRetrievalController {

    private final RetrievalDispatcher retrievalDispatcher;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseAccessEvaluator accessEvaluator;
    private final ExecutionEventService executionEventService;
    private final ai.myrmec.engine.security.injection.UntrustedContentWrapper untrustedContentWrapper;

    @Operation(
            summary = "Run a retrieval query",
            description = "Dispatch a retrieval query against the given knowledge base and "
                    + "return up to topK hits. ACL: the calling agent's project must have "
                    + "access to the knowledge base."
    )
    @PostMapping
    public ResponseEntity<List<RetrievalResponse>> retrieve(
            @Valid @RequestBody RetrievalRequest request,
            @AuthenticationPrincipal AgentPrincipal principal) {
        // ACL (#27): an agent may only retrieve from a knowledge base reachable
        // from its owning project's scope. An unknown KB or a denied KB returns
        // an empty result (no existence leak); the agent answers ungrounded.
        Optional<KnowledgeBase> kb = knowledgeBaseRepository.findById(request.knowledgeBaseId());
        if (kb.isEmpty()) {
            log.warn("Agent {} requested retrieval from unknown KB {}",
                    principal.getName(), request.knowledgeBaseId());
            return ResponseEntity.ok(List.of());
        }
        UUID agentProjectId = accessEvaluator.resolveAgentProjectId(principal.getAgentId());
        if (!accessEvaluator.canRetrieve(kb.get(), agentProjectId)) {
            log.warn("Agent {} (project {}) denied retrieval from KB {} (scope {})",
                    principal.getName(), agentProjectId,
                    request.knowledgeBaseId(), kb.get().getScope());
            return ResponseEntity.ok(List.of());
        }

        Map<String, String> filters = request.filters() == null ? Map.of() : request.filters();
        RetrievalQuery query = new RetrievalQuery(
                request.knowledgeBaseId(),
                request.query(),
                request.topK(),
                filters);
        try {
            List<RetrievalResult> hits = retrievalDispatcher.dispatch(query);
            // Audit trail (#32): record a RETRIEVAL execution event when the
            // agent supplied task context, capturing which chunks fed the
            // answer (IDs + scores only, never passage text) for AUDITOR replay.
            recordRetrievalEvent(request, hits);
            // Phase 9f — wrap each passage in <untrusted> envelope so a
            // prompt-injection attempt embedded in a retrieved
            // document cannot escape into the trusted system frame.
            return ResponseEntity.ok(hits.stream()
                    .map(h -> RetrievalResponse.from(h, untrustedContentWrapper))
                    .toList());
        } catch (RetrievalException e) {
            // Per RetrievalProvider contract, treat provider failure as
            // empty result + log; agents may then answer without RAG grounding.
            log.warn("Retrieval failed for KB {}: {}", request.knowledgeBaseId(), e.getMessage(), e);
            return ResponseEntity.ok(List.of());
        }
    }

    /**
     * Record a RETRIEVAL audit event when the agent threaded a task id.
     * No-op for contextless retrievals (execution events are keyed on a task).
     */
    private void recordRetrievalEvent(RetrievalRequest request, List<RetrievalResult> hits) {
        if (request.taskId() == null) {
            return;
        }
        List<UUID> chunkIds = hits.stream().map(h -> h.citation().chunkId()).toList();
        List<UUID> sourceIds = hits.stream().map(h -> h.citation().sourceId()).toList();
        List<Double> scores = hits.stream().map(h -> h.citation().score()).toList();
        try {
            executionEventService.recordRetrieval(
                    request.taskId(), request.attemptId(), request.knowledgeBaseId(),
                    request.query(), request.topK(), chunkIds, sourceIds, scores);
        } catch (RuntimeException e) {
            // Auditing must never break a live retrieval; log and move on.
            log.warn("Failed to record RETRIEVAL event for task {} KB {}: {}",
                    request.taskId(), request.knowledgeBaseId(), e.getMessage());
        }
    }
}
