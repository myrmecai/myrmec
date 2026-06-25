package ai.myrmec.engine.knowledge.rag.dto;

import ai.myrmec.engine.knowledge.rag.KnowledgeSource;

import java.time.Instant;
import java.util.UUID;

/**
 * Wire representation of a {@link KnowledgeSource} including its last-sync
 * bookkeeping and current persisted chunk count.
 */
public record KnowledgeSourceResponse(
        UUID id,
        UUID knowledgeBaseId,
        String connectorType,
        String name,
        String uri,
        String syncSchedule,
        boolean enabled,
        Instant lastSyncAt,
        String lastSyncStatus,
        Long lastSyncChunks,
        Integer lastSyncErrorCount,
        Long lastSyncDurationMs,
        long chunkCount,
        Instant createdAt,
        Instant updatedAt
) {

    public static KnowledgeSourceResponse from(KnowledgeSource source, long chunkCount) {
        return new KnowledgeSourceResponse(
                source.getId(),
                source.getKnowledgeBaseId(),
                source.getConnectorType(),
                source.getName(),
                source.getUri(),
                source.getSyncSchedule(),
                source.isEnabled(),
                source.getLastSyncAt(),
                source.getLastSyncStatus(),
                source.getLastSyncChunks(),
                source.getLastSyncErrorCount(),
                source.getLastSyncDurationMs(),
                chunkCount,
                source.getCreatedAt(),
                source.getUpdatedAt());
    }
}
