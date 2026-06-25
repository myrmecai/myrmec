package ai.myrmec.engine.websocket;

import ai.myrmec.engine.agent.AgentService;
import ai.myrmec.engine.conversation.dispatch.PendingTurnRegistry;
import ai.myrmec.engine.node.NodeRegistryService;
import ai.myrmec.engine.websocket.message.CloseCode;
import ai.myrmec.engine.websocket.message.MessageType;
import ai.myrmec.engine.websocket.message.WebSocketMessage;
import ai.myrmec.engine.websocket.message.payload.ConversationAttachPayload;
import ai.myrmec.engine.websocket.message.payload.ConversationTurnAssignPayload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.UUID;

/**
 * Handler for the conversation-scoped WebSocket (agent-concurrency §9.4/§9.8).
 *
 * <p>A worker that received {@code agent.bind} dials the named home node
 * directly and opens this socket for exactly one conversation. Its first
 * frame is {@code conversation.attach}: the engine authenticates it (the
 * agent JWT was already validated in the handshake, pinning
 * {@code agentInstanceId}), flips the worker to {@code BOUND}, pins the home
 * node on the worker + conversation, and registers the socket in the
 * per-replica {@link ConversationSocketRegistry}.</p>
 *
 * <p>Slice 4c-2 completes the turn-stream cutover: once the socket is
 * registered, the engine flushes the buffered {@code conversation.turn.assign}
 * over it, and the worker streams {@code message.delta} /
 * {@code message.complete} / {@code task.cancelled} back on the same socket.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentConversationWebSocketHandler extends TextWebSocketHandler {

    private final AgentService agentService;
    private final NodeRegistryService nodeRegistry;
    private final ConversationSocketRegistry conversationSocketRegistry;
    private final PendingTurnRegistry pendingTurnRegistry;
    private final ConversationInboundService conversationInboundService;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        UUID agentInstanceId =
                (UUID) session.getAttributes().get(AgentWebSocketHandshakeInterceptor.ATTR_AGENT_INSTANCE_ID);
        if (agentInstanceId == null) {
            log.warn("Conversation socket opened without an agent instance id \u2014 closing");
            closeQuietly(session, CloseCode.INVALID_TOKEN);
            return;
        }
        log.debug("Conversation socket opened for agent instance {} (awaiting attach)", agentInstanceId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        UUID agentInstanceId =
                (UUID) session.getAttributes().get(AgentWebSocketHandshakeInterceptor.ATTR_AGENT_INSTANCE_ID);
        if (agentInstanceId == null) {
            closeQuietly(session, CloseCode.INVALID_TOKEN);
            return;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(message.getPayload());
        } catch (Exception e) {
            log.warn("Unparseable frame on conversation socket from agent {}: {}",
                    agentInstanceId, e.getMessage());
            return;
        }
        String type = root.path("type").asText(null);
        JsonNode payload = root.path("payload");
        if (MessageType.CONVERSATION_ATTACH.equals(type)) {
            handleAttach(session, agentInstanceId, payload);
            return;
        }
        // Slice 4c-2 — conversational output rides this socket. The frames are
        // keyed by conversationId in their payload and the worker is already
        // JWT-pinned to agentInstanceId, so the shared service handles them
        // identically to the (now-retired) control-socket path.
        switch (type == null ? "" : type) {
            case MessageType.MESSAGE_DELTA ->
                    conversationInboundService.onMessageDelta(agentInstanceId, payload, message.getPayload());
            case MessageType.MESSAGE_COMPLETE ->
                    conversationInboundService.onMessageComplete(agentInstanceId, payload, message.getPayload());
            case MessageType.TASK_CANCELLED ->
                    conversationInboundService.onTaskCancelled(agentInstanceId, payload, message.getPayload());
            case MessageType.APPROVAL_REQUEST ->
                    conversationInboundService.onApprovalRequest(agentInstanceId, payload);
            default -> log.debug("Ignoring unsupported frame type '{}' on conversation socket from agent {}",
                    type, agentInstanceId);
        }
    }

    /**
     * {@code conversation.attach} — bind the worker to its conversation and
     * register this socket. Rejects (and closes) when the worker is not
     * reserved for the conversation it claims, or when the body disagrees
     * with the JWT-pinned instance.
     */
    private void handleAttach(WebSocketSession session, UUID agentInstanceId, JsonNode payload) {
        ConversationAttachPayload attach;
        try {
            attach = objectMapper.convertValue(payload, ConversationAttachPayload.class);
        } catch (Exception e) {
            log.warn("Malformed conversation.attach from agent {}: {}", agentInstanceId, e.getMessage());
            closeQuietly(session, CloseStatus.BAD_DATA);
            return;
        }
        if (attach.getConversationId() == null) {
            log.warn("conversation.attach from agent {} missing conversationId \u2014 closing", agentInstanceId);
            closeQuietly(session, CloseStatus.BAD_DATA);
            return;
        }
        // Defensive: the body's agentId (if present) must match the
        // JWT-pinned instance — a worker cannot attach as someone else.
        if (attach.getAgentId() != null && !attach.getAgentId().equals(agentInstanceId)) {
            log.warn("conversation.attach agentId {} \u2260 authenticated instance {} \u2014 closing",
                    attach.getAgentId(), agentInstanceId);
            closeQuietly(session, CloseCode.INVALID_TOKEN);
            return;
        }

        // The socket lands on the home node by construction (the worker dialed
        // this replica's address), so "self" is the home node.
        String homeNodeId = nodeRegistry.getSelfNodeId();
        boolean bound;
        try {
            bound = agentService.attachConversation(
                    agentInstanceId, attach.getConversationId(), homeNodeId);
        } catch (Exception e) {
            // H2 concurrent-update on the agents row (confirmBind racing
            // attachConversation on two sockets for the same instance) can
            // throw. The worker is already RESERVED for this conversation;
            // register the socket and flush the turn anyway — the state
            // transition is best-effort and the event log is already
            // fault-tolerant (REQUIRES_NEW + catch).
            log.warn("attachConversation threw for agent {} conv {} — registering socket anyway: {}",
                    agentInstanceId, attach.getConversationId(), e.getMessage());
            bound = true;
        }
        if (!bound) {
            log.warn("conversation.attach rejected for agent {} conv {} \u2014 closing socket",
                    agentInstanceId, attach.getConversationId());
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        conversationSocketRegistry.register(attach.getConversationId(), session);
        log.info("Agent {} attached conversation socket for conv {} (home {})",
                agentInstanceId, attach.getConversationId(), homeNodeId);

        // Slice 4c-2 — flush the turn the dispatcher buffered while waiting for
        // this socket to attach (agent-concurrency §9.4). The turn was held in
        // PendingTurnRegistry keyed by conversationId; deliver it now over the
        // freshly-registered socket.
        UUID conversationId = attach.getConversationId();
        pendingTurnRegistry.take(conversationId).ifPresent(turn -> {
            WebSocketMessage<ConversationTurnAssignPayload> message =
                    WebSocketMessage.of(MessageType.CONVERSATION_TURN_ASSIGN, turn);
            boolean delivered = conversationSocketRegistry.sendMessage(conversationId, message);
            if (delivered) {
                log.info("Flushed buffered turn to agent {} over conversation socket (conv {})",
                        agentInstanceId, conversationId);
            } else {
                log.warn("Could not flush buffered turn for conv {} — socket vanished immediately after attach",
                        conversationId);
            }
        });
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        // Detach the socket from the registry. Worker draining / release on a
        // dropped conversation socket is wired in slice 4d; for 4c the turn's
        // own completion (MESSAGE_COMPLETE on the control path) still releases
        // the worker, so we only clean up the socket map here.
        //
        // Slice #106a (agent-concurrency §9.11) — distinguish a clean close
        // (normal drain / task.complete teardown) from an abnormal one (the
        // socket dropped mid-conversation). On an ABNORMAL close the engine
        // takes NO re-home action: recovery is owned elsewhere and is keyed on
        // whether THIS home node is up or down, which keeps re-attach
        // idempotent and avoids a double-recovery race —
        //   • home node still UP  → the Agent Supervisor reconnects to this
        //     same node and re-sends an idempotent conversation.attach
        //     (CONVERSATION_REATTACHED); the worker stays BOUND meanwhile.
        //   • home node DOWN       → the HomeNodeFailoverService sweep re-homes
        //     the worker to a live replica (HOME_NODE_LOST → INSTANCE_BOUND).
        //   • agent host gone       → the HOST_LOST reaper owns it (* → DEAD).
        boolean cleanClose = isCleanClose(status);
        conversationSocketRegistry.unregisterBySession(session).ifPresent(conversationId -> {
            if (cleanClose) {
                log.debug("Conversation socket closed cleanly for conv {} (status {})",
                        conversationId, status);
            } else {
                log.info("Conversation socket dropped abnormally for conv {} (status {}); "
                        + "engine takes no re-home action — awaiting Agent reconnect or failover sweep",
                        conversationId, status);
            }
        });
    }

    /**
     * A close is "clean" when it carries a graceful close code — a normal
     * teardown (drain / {@code task.complete}) rather than a socket that
     * dropped mid-conversation. {@code NORMAL} (1000) and {@code GOING_AWAY}
     * (1001) are graceful; {@code NO_CLOSE_FRAME} (1006) and the policy /
     * server-error codes signal an abnormal drop.
     */
    private static boolean isCleanClose(CloseStatus status) {
        if (status == null) {
            return false;
        }
        int code = status.getCode();
        return code == CloseStatus.NORMAL.getCode()
                || code == CloseStatus.GOING_AWAY.getCode();
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception ignored) {
            // best-effort
        }
    }
}
