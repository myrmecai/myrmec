package ai.myrmec.engine.assistant.dto;

import ai.myrmec.engine.assistant.Assistant;

import java.time.Instant;
import java.util.UUID;

/** Parent-row view of an {@link Assistant} (#92). */
public record AssistantResponse(
        UUID id,
        UUID projectId,
        String name,
        String description,
        UUID currentVersionId,
        boolean disabled,
        Instant archivedAt,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt
) {
    public static AssistantResponse from(Assistant a) {
        return new AssistantResponse(
                a.getId(),
                a.getProjectId(),
                a.getName(),
                a.getDescription(),
                a.getCurrentVersionId(),
                a.isDisabled(),
                a.getArchivedAt(),
                a.getCreatedBy(),
                a.getCreatedAt(),
                a.getUpdatedAt()
        );
    }
}
