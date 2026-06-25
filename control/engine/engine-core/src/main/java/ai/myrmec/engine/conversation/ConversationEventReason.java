package ai.myrmec.engine.conversation;

/**
 * Reason-code catalog for {@link ConversationEvent} rows
 * (conversation-observability §4.1, agent-concurrency §9.5).
 *
 * <p>The operational FSM stays minimal; every analytical distinction is a
 * reason code on a transition in the append-only event log, never an extra
 * state. This V1 catalog covers the warm-worker bind lifecycle that the
 * engine drives today (reserve → ack → attach → release, plus the three
 * reaper timeouts). Conversation-session reason codes (e.g.
 * {@code SESSION_REQUESTED}, {@code USER_ENDED}) layer in with the session
 * FSM (PENDING → ACTIVE ⇄ IDLE → CLOSED) in a later slice.</p>
 */
public enum ConversationEventReason {

    /** A replica atomically claimed a warm worker: {@code IDLE → RESERVED}. */
    RESERVED,

    /** The Agent Host acked {@code agent.bind}: {@code RESERVED → CONNECTING}. */
    BIND_ACKED,

    /** The Agent Host nacked {@code agent.bind}: worker released to {@code IDLE}. */
    BIND_NACKED,

    /**
     * The conversation socket attached on the home node:
     * {@code CONNECTING/RESERVED → BOUND} (the connect-time stamp).
     */
    INSTANCE_BOUND,

    /** Normal release back into the warm pool: {@code → IDLE}. */
    RELEASED,

    /** Host never acked the bind in time; slot reclaimed: {@code RESERVED → IDLE}. */
    RESERVE_TIMEOUT,

    /** Worker never attached in time; slot reclaimed: {@code CONNECTING → IDLE}. */
    CONNECT_TIMEOUT,

    /** Control-socket heartbeat missed; the Host's workers reaped: {@code * → DEAD}. */
    HOST_LOST,

    /**
     * Conversation socket re-attached to the <i>same</i> home node after a
     * transient drop: {@code BOUND → BOUND} (agent-concurrency §9.11). A
     * liveness event only — the worker stays {@code BOUND}; the Agent
     * Supervisor reconnected and re-sent an idempotent
     * {@code conversation.attach}, so no second {@link #INSTANCE_BOUND} is
     * emitted.
     */
    CONVERSATION_REATTACHED,

    /**
     * Home node is {@code DOWN}; the engine sweep re-homed the worker to a
     * live replica: {@code BOUND → CONNECTING} (agent-concurrency §9.11).
     * Clears {@code home_node_id} and reuses the {@link #CONNECT_TIMEOUT}
     * reaper clock so a failed re-home is reclaimed by the existing path.
     */
    HOME_NODE_LOST
}
