package ai.myrmec.engine.websocket.message;

/**
 * WebSocket message types for Engine ↔ Agent communication.
 */
public final class MessageType {
    
    private MessageType() {}
    
    // ==================== Engine → Agent ====================
    
    /** Task assignment - contains full task details */
    public static final String TASK_ASSIGN = "task.assign";
    
    /** Task cancellation request */
    public static final String TASK_CANCEL = "task.cancel";
    
    /** Task status inquiry (sent on agent reconnect) */
    public static final String TASK_STATUS_REQUEST = "task.status_request";
    
    /** Keep-alive ping - agent must respond with pong */
    public static final String PING = "ping";
    
    // ==================== Agent → Engine ====================
    
    /** Agent accepts assigned task (within 5s timeout) */
    public static final String TASK_ACCEPT = "task.accept";
    
    /** Agent rejects task (busy, incompatible, etc.) */
    public static final String TASK_REJECT = "task.reject";
    
    /** Task progress update (0-100%) */
    public static final String TASK_PROGRESS = "task.progress";
    
    /** Task completed successfully */
    public static final String TASK_COMPLETE = "task.complete";
    
    /** Task failed with error */
    public static final String TASK_FAILED = "task.failed";
    
    /** Task skipped (not executed, does not count toward retry limit) */
    public static final String TASK_SKIPPED = "task.skipped";
    
    /** Response to task.status_request */
    public static final String TASK_STATUS_RESPONSE = "task.status_response";
    
    /** Log entry from agent */
    public static final String LOG = "log";
    
    /** LLM tool invocation started */
    public static final String TOOL_CALL = "tool.call";
    
    /** Tool invocation completed */
    public static final String TOOL_RESULT = "tool.result";
    
    /** Response to ping */
    public static final String PONG = "pong";
    
    /** Agent graceful shutdown notification */
    public static final String DISCONNECT = "disconnect";

    /** LLM token usage from a single model call */
    public static final String TOKEN_USAGE = "token.usage";

    /** Per-task aggregated metrics summary (sent before task.complete/task.failed) */
    public static final String TASK_METRICS = "task.metrics";
}
