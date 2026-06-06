package ai.myrmec.engine.workflow;

/**
 * Status of a workflow task execution.
 */
public enum TaskStatus {
    /** Task created, waiting for dependencies */
    PENDING,
    /** Task is ready to be picked up by an agent */
    READY,
    /** Task is assigned to an agent and running */
    RUNNING,
    /** Task completed (check result for SUCCESS/FAILURE/TIMEOUT) */
    COMPLETED,
    /** Task was cancelled */
    CANCELLED
}
