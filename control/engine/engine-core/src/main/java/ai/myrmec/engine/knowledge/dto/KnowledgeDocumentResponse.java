package ai.myrmec.engine.knowledge.dto;

import ai.myrmec.engine.knowledge.KnowledgeCategory;
import ai.myrmec.engine.knowledge.KnowledgeDocument;
import ai.myrmec.engine.knowledge.KnowledgeScope;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response DTO for knowledge document.
 */
public record KnowledgeDocumentResponse(
        UUID id,
        KnowledgeScope scope,
        UUID projectId,
        KnowledgeCategory category,
        String name,
        String content,
        int priority,
        List<String> appliesTo,
        String sourcePath,
        boolean active,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt
) {
    /**
     * Create response from entity.
     */
    public static KnowledgeDocumentResponse from(KnowledgeDocument doc) {
        return new KnowledgeDocumentResponse(
                doc.getId(),
                doc.getScope(),
                doc.getProjectId(),
                doc.getCategory(),
                doc.getName(),
                doc.getContent(),
                doc.getPriority(),
                doc.getAppliesTo(),
                doc.getSourcePath(),
                doc.isActive(),
                doc.getCreatedBy(),
                doc.getCreatedAt(),
                doc.getUpdatedAt()
        );
    }
}
