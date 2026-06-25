package ai.myrmec.engine.mywork.dto;

import ai.myrmec.engine.assistant.Assistant;
import ai.myrmec.engine.workflow.Workflow;

import java.time.Instant;
import java.util.UUID;

/**
 * One row in the My Work "Archived" tab (UC-013) — an archived service
 * definition (a soft-archived Workflow or an archived Assistant). Sessions and
 * executions never appear here; past runs belong to the History tab (#84). The
 * {@code type} facet distinguishes the two service kinds.
 */
public record MyArchivedRow(
        UUID id,
        UUID projectId,
        String projectName,
        Type type,
        String name,
        String description,
        Instant archivedAt) {

    /** Service kind of the archived definition, surfaced as the tab's {@code Type} facet. */
    public enum Type {
        WORKFLOW,
        ASSISTANT
    }

    public static MyArchivedRow ofWorkflow(Workflow w, String projectName) {
        return new MyArchivedRow(
                w.getId(),
                w.getProject().getId(),
                projectName,
                Type.WORKFLOW,
                w.getName(),
                w.getDescription(),
                w.getUpdatedAt());
    }

    public static MyArchivedRow ofAssistant(Assistant a, String projectName) {
        return new MyArchivedRow(
                a.getId(),
                a.getProjectId(),
                projectName,
                Type.ASSISTANT,
                a.getName(),
                a.getDescription(),
                a.getArchivedAt());
    }
}
