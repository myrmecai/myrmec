package ai.myrmec.engine.mywork.dto;

import ai.myrmec.engine.assistant.Assistant;

import java.time.Instant;
import java.util.UUID;

/**
 * One row in the My Work "Conversations" tab (UC-013) — an Assistant definition
 * with its live session rollup. The row entity is the Assistant (not individual
 * sessions); {@code activeSessions} counts that assistant's currently-ACTIVE
 * conversations (internal + external), {@code lastSessionAt} is the most recent
 * session activity.
 *
 * <p>{@code status} is the user-facing lifecycle string derived from the parent
 * row: {@code ARCHIVED} (archivedAt set) &gt; {@code DISABLED} (disabled) &gt;
 * {@code PUBLISHED} (has a current published version) &gt; {@code DRAFT}.
 */
public record MyAssistantRow(
        UUID id,
        UUID projectId,
        String projectName,
        String name,
        String description,
        String status,
        long activeSessions,
        Instant lastSessionAt) {

    /** Derive the user-facing lifecycle status from the parent Assistant row. */
    public static String deriveStatus(Assistant a) {
        if (a.getArchivedAt() != null) return "ARCHIVED";
        if (a.isDisabled()) return "DISABLED";
        return a.getCurrentVersionId() != null ? "PUBLISHED" : "DRAFT";
    }

    public static MyAssistantRow of(Assistant a, String projectName, long activeSessions, Instant lastSessionAt) {
        return new MyAssistantRow(
                a.getId(),
                a.getProjectId(),
                projectName,
                a.getName(),
                a.getDescription(),
                deriveStatus(a),
                activeSessions,
                lastSessionAt);
    }
}
