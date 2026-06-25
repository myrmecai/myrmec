package ai.myrmec.engine.knowledge.rag.dto;

import ai.myrmec.engine.knowledge.rag.KnowledgeBase;

import java.time.Instant;
import java.util.UUID;

/**
 * Wire representation of a {@link KnowledgeBase} for the management UI.
 */
public record KnowledgeBaseResponse(
        UUID id,
        String name,
        String description,
        String scope,
        UUID projectId,
        String providerId,
        String status,
        String classification,
        boolean allowAssistantBinding,
        long sourceCount,
        Instant createdAt,
        Instant updatedAt
) {

    public static KnowledgeBaseResponse from(KnowledgeBase kb, long sourceCount) {
        return new KnowledgeBaseResponse(
                kb.getId(),
                kb.getName(),
                kb.getDescription(),
                kb.getScope() == null ? null : kb.getScope().name(),
                kb.getProjectId(),
                kb.getProviderId(),
                kb.getStatus() == null ? null : kb.getStatus().name(),
                kb.getClassification(),
                kb.isAllowAssistantBinding(),
                sourceCount,
                kb.getCreatedAt(),
                kb.getUpdatedAt());
    }
}
