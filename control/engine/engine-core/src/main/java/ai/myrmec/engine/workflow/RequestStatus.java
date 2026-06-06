package ai.myrmec.engine.workflow;

/**
 * Status of a workflow request execution.
 */
public enum RequestStatus {
    /** Request created, waiting to start */
    PENDING,
    /** Request is actively running */
    RUNNING,
    /** Request completed successfully */
    COMPLETED,
    /** Request failed with an error */
    FAILED,
    /** Request was cancelled by user */
    CANCELLED,
    /** Request timed out */
    TIMEOUT
}
