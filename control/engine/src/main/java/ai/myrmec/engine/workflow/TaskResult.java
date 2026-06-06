package ai.myrmec.engine.workflow;

/**
 * Result of a completed task - used for conditional routing.
 */
public enum TaskResult {
    /** Task completed successfully */
    SUCCESS,
    /** Task failed with an error */
    FAILURE,
    /** Task timed out */
    TIMEOUT
}
