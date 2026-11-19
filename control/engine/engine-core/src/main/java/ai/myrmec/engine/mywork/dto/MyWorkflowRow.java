package ai.myrmec.engine.mywork.dto;

import ai.myrmec.engine.workflow.RequestStatus;
import ai.myrmec.engine.workflow.Workflow;
import ai.myrmec.engine.workflow.WorkflowRequest;
import ai.myrmec.engine.workflow.WorkflowStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row in the My Work "Workflows" tab (UC-013) — a workflow definition with
 * its cross-Team context and live execution rollup. {@code activeExecutions}
 * counts in-flight runs (PENDING / RUNNING); {@code lastExecution*} reflect the
 * most recent run, or null when the workflow has never executed.
 */
public record MyWorkflowRow(
        UUID id,
        UUID teamId,
        String teamName,
        String name,
        String description,
        WorkflowStatus status,
        Integer version,
        long activeExecutions,
        Instant lastExecutionAt,
        RequestStatus lastExecutionStatus) {

    /** Statuses that count a {@link WorkflowRequest} as in-flight ("active"). */
    private static boolean isActive(RequestStatus s) {
        return s == RequestStatus.PENDING || s == RequestStatus.RUNNING;
    }

    /**
     * Build a row from a workflow and all of its execution requests. The caller
     * supplies the resolved Team name (it already holds the team) so this
     * factory never touches the lazy {@code Workflow.team} association.
     */
    public static MyWorkflowRow of(Workflow workflow, String teamName, List<WorkflowRequest> requests) {
        long active = requests.stream().filter(r -> isActive(r.getStatus())).count();
        WorkflowRequest latest = requests.stream()
                .max((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .orElse(null);
        return new MyWorkflowRow(
                workflow.getId(),
                workflow.getTeam().getId(),
                teamName,
                workflow.getName(),
                workflow.getDescription(),
                workflow.getStatus(),
                workflow.getVersion(),
                active,
                latest == null ? null : latest.getCreatedAt(),
                latest == null ? null : latest.getStatus());
    }
}
