package ai.myrmec.engine.workflow;

/**
 * Status of a workflow definition.
 */
public enum WorkflowStatus {
    /** Workflow is being edited, not yet runnable */
    DRAFT,
    /** Workflow is published and can accept requests */
    PUBLISHED,
    /** Workflow is disabled and won't accept new requests */
    DISABLED,
    /** Workflow is archived (read-only, historical) */
    ARCHIVED
}
