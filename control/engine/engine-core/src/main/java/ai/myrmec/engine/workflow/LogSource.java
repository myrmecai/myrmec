package ai.myrmec.engine.workflow;

/**
 * Source of a log event.
 * 
 * <p>Used to differentiate between:
 * <ul>
 *   <li>TASK - Logs from task execution (context.log_info, etc.)</li>
 *   <li>AGENT - Logs from Python logging module (agent-level debugging)</li>
 *   <li>SYSTEM - Logs from stdout/stderr capture</li>
 * </ul>
 */
public enum LogSource {
    /**
     * Task execution logs (via TaskContext.log_*).
     */
    TASK,

    /**
     * Agent-level logs (via Python logging module).
     */
    AGENT,

    /**
     * System output (stdout/stderr capture).
     */
    SYSTEM
}
