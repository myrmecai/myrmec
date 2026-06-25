package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine.knowledge.rag.dto.KnowledgeCapabilitiesResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only catalogue of the knowledge connector types and retrieval providers
 * registered in this engine, used to populate the management UI pickers.
 * Available to any authenticated principal.
 */
@RestController
@RequestMapping("/api/v1/knowledge/capabilities")
@RequiredArgsConstructor
public class KnowledgeCapabilitiesController {

    private final ConnectorDispatcher connectorDispatcher;
    private final RetrievalDispatcher retrievalDispatcher;

    @GetMapping
    public KnowledgeCapabilitiesResponse get() {
        return new KnowledgeCapabilitiesResponse(
                connectorDispatcher.connectorTypes().stream().sorted().toList(),
                retrievalDispatcher.providerIds().stream().sorted().toList());
    }
}
