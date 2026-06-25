package ai.myrmec.engine.knowledge.rag.dto;

import java.util.List;

/**
 * Available connector types and retrieval-provider ids, used to populate the
 * knowledge-base management UI pickers.
 */
public record KnowledgeCapabilitiesResponse(
        List<String> connectorTypes,
        List<String> providerIds
) {
}
