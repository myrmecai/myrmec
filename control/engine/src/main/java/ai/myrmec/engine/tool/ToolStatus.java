package ai.myrmec.engine.tool;

/**
 * Status of a tool in the registry.
 */
public enum ToolStatus {
    /** Tool is available for use */
    ACTIVE,
    /** Tool is temporarily disabled */
    DISABLED,
    /** Tool is deprecated and should not be used for new workflows */
    DEPRECATED
}
