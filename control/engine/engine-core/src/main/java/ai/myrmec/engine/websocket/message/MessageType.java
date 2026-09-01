// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

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

    /**
     * Conversational turn assignment (Phase 6d). Sent when a USER
     * message lands and the engine wants the agent to produce one
     * assistant turn. Carries the full conversation context (system
     * prompt, pinned facts, sliding window, model handle) so the agent
     * can run an LLM call and stream the result back as
     * {@link #MESSAGE_DELTA} + {@link #MESSAGE_COMPLETE}.
     */
    public static final String CONVERSATION_TURN_ASSIGN = "conversation.turn.assign";

    /**
     * Engine → Agent (conversation socket). Cancel the in-flight assistant
     * turn for a conversation. The bound worker breaks out of its streaming /
     * tool loop and acknowledges with {@link #TASK_CANCELLED}, carrying any
     * partial text it had already generated.
     */
    public static final String CONVERSATION_TURN_CANCEL = "conversation.turn.cancel";

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

    // ==================== Phase 6b — Conversational sessions (Agent → Engine) ====================

    /**
     * Streamed assistant token delta during a CONVERSATIONAL turn. The agent
     * emits zero-or-more of these between a USER message landing and the
     * final {@link #MESSAGE_COMPLETE}. Engine fans them out to every WS
     * viewer (Phase 6c broker).
     */
    public static final String MESSAGE_DELTA = "message.delta";

    /**
     * Final marker for an assistant turn. Carries the full canonical text
     * (sum of deltas) plus an optional token-count summary. Engine persists
     * this as a {@code ConversationMessage} row with role=ASSISTANT.
     */
    public static final String MESSAGE_COMPLETE = "message.complete";

    /**
     * Agent acknowledgement of a {@link #TASK_CANCEL}. Sent after the agent
     * has stopped streaming and released resources. Engine treats this as
     * authoritative confirmation that the cancellation took effect.
     */
    public static final String TASK_CANCELLED = "task.cancelled";

    // ==================== Phase 7c — HITL approvals ====================

    /**
     * Agent → Engine. The agent is about to perform a side-effecting
     * action and is asking the human in the loop for permission before
     * proceeding. Engine persists this as an APPROVAL_REQUEST row on
     * the conversation and broadcasts the same frame to viewers (so
     * the chat UI can render an approval card).
     */
    public static final String APPROVAL_REQUEST = "approval.request";

    /**
     * Engine → Agent. The decision corresponding to a previously
     * submitted {@link #APPROVAL_REQUEST}. {@code clientRequestId}
     * echoes the agent-supplied id so the SDK can resolve the matching
     * future.
     */
    public static final String APPROVAL_DECISION = "approval.decision";

    // ==================== Slice 2 — Agent Host control (Host → Engine) ====================

    /**
     * Agent Host → Engine. The Supervisor advertises its installed
     * capabilities ({@code provisions}: tools + runtime) and the CPU/RAM it
     * auto-sized from ({@code reportedCapacity}). Sent on each control-socket
     * (re)connect; the host is the source of truth, so the engine overwrites
     * the AgentHost's provisions/capacity each time.
     */
    public static final String HOST_ANNOUNCE = "host.announce";

    // ==================== Slice 3 — reserve-time binding (Engine → Agent) ====================

    /**
     * Engine → Agent. Reserve-time binding: the engine has atomically
     * claimed this warm worker for a specific conversation and pinned a
     * profile version. The frame tells the worker which conversation it is
     * now serving and which profile version to load (agent-concurrency
     * §9.5). Single-node strangler: the binding rides the worker's existing
     * control socket; the dedicated conversation socket + node addressing
     * arrive in a later slice.
     */
    public static final String AGENT_BIND = "agent.bind";

    /**
     * Engine → Agent. Release this worker from its current binding back into
     * the warm pool. Sent when the engine tears a binding down (turn done,
     * cancelled, or timed out); the worker drops its conversation context
     * and returns to {@code IDLE}.
     */
    public static final String AGENT_RELEASE = "agent.release";

    // ==================== Slice 4c — conversation socket (Agent → Engine) ====================

    /**
     * Agent → Engine. First frame on a freshly-opened, conversation-scoped
     * WebSocket. A worker that received {@link #AGENT_BIND} dials the named
     * home node directly and sends this to attach its conversation socket;
     * the engine authenticates it, registers it in the local
     * {@code conversationId → socket} map, and flips the worker to
     * {@code BOUND} (agent-concurrency §9.4/§9.5).
     */
    public static final String CONVERSATION_ATTACH = "conversation.attach";

    // ==================== Slice 4d — bind handshake (Host → Engine) ====================

    /**
     * Agent Host → Engine. The host received an {@link #AGENT_BIND} and its
     * worker is now dialing the home node to open its conversation socket.
     * The engine advances the reserved worker {@code RESERVED -> CONNECTING}
     * and restarts the connect-timeout reaper clock (agent-concurrency §9.5).
     */
    public static final String AGENT_BIND_ACK = "agent.bind.ack";

    /**
     * Agent Host → Engine. The host cannot serve an {@link #AGENT_BIND}
     * (worker spawn failed, capacity gone, etc.). The engine releases the
     * reserved worker straight back to {@code IDLE} so the dispatcher can
     * re-pick another candidate (agent-concurrency §9.5).
     */
    public static final String AGENT_BIND_NACK = "agent.bind.nack";

    // ==================== Unified Inference Dispatch (§5.1) ====================

    /** Engine → Agent. Establish a session; deliver workspace, tool catalog, KB catalog, model. Sent once. */
    public static final String SESSION_OPEN = "session.open";

    /** Engine → Agent. Tear down a session (conversation unbind / execution end). */
    public static final String SESSION_CLOSE = "session.close";

    /** Engine → Agent. One LLM turn/step: assembled messages[], active tools, generation config. */
    public static final String INFERENCE_ASSIGN = "inference.assign";

    /** Agent → Engine. Agent acknowledges it has started the turn. */
    public static final String INFERENCE_ACCEPT = "inference.accept";

    /** Agent → Engine. Streaming text chunk (only when stream=true). */
    public static final String INFERENCE_DELTA = "inference.delta";

    /** Agent → Engine. Agent's model requested a tool. */
    public static final String INFERENCE_TOOL_CALL = "inference.tool_call";

    /** Agent → Engine. Result of a tool execution. */
    public static final String INFERENCE_TOOL_RESULT = "inference.tool_result";

    /** Agent → Engine. Final assistant content + token usage. */
    public static final String INFERENCE_COMPLETE = "inference.complete";

    /** Agent → Engine. Turn failed (with error code/hint). */
    public static final String INFERENCE_FAILED = "inference.failed";

    /** Engine → Agent. Cancel an in-flight turn. */
    public static final String INFERENCE_CANCEL = "inference.cancel";

    /** Agent → Engine. Ack of cancellation (+ partial content). */
    public static final String INFERENCE_CANCELLED = "inference.cancelled";
}
