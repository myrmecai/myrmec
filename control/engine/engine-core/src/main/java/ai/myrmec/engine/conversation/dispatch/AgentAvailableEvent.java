package ai.myrmec.engine.conversation.dispatch;

import java.util.UUID;

/**
 * Published when an agent instance becomes available to serve conversation
 * turns — currently when its control WebSocket connects (and the instance is
 * released back to IDLE). The {@link ConversationBacklogDrainer} listens for
 * this to re-dispatch conversations whose USER turn was buffered because no
 * worker was online at post time (#86 / #87).
 *
 * <p>Decoupled as a Spring {@code ApplicationEvent} so the WebSocket handler
 * doesn't depend on the drainer (which in turn depends on the dispatcher, which
 * already depends on the handler — a direct edge would form a cycle).</p>
 *
 * @param agentInstanceId the worker that just became available
 */
public record AgentAvailableEvent(UUID agentInstanceId) {
}
