package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine.knowledge.rag.dto.RetrievalRequest;
import ai.myrmec.engine.knowledge.rag.dto.RetrievalResponse;
import ai.myrmec.engine.spi.retrieval.RetrievalException;
import ai.myrmec.engine.spi.retrieval.RetrievalQuery;
import ai.myrmec.engine.spi.retrieval.RetrievalResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

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
    private final ai.myrmec.engine.security.injection.UntrustedContentWrapper untrustedContentWrapper;

    @Operation(
            summary = "Run a retrieval query",
            description = "Dispatch a retrieval query against the given knowledge base and "
                    + "return up to topK hits. ACL: the calling agent's project must have "
                    + "access to the knowledge base."
    )
    @PostMapping
    public ResponseEntity<List<RetrievalResponse>> retrieve(@Valid @RequestBody RetrievalRequest request) {
        Map<String, String> filters = request.filters() == null ? Map.of() : request.filters();
        RetrievalQuery query = new RetrievalQuery(
                request.knowledgeBaseId(),
                request.query(),
                request.topK(),
                filters);
        try {
            List<RetrievalResult> hits = retrievalDispatcher.dispatch(query);
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
}
