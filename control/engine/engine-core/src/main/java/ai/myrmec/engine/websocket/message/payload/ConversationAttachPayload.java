package ai.myrmec.engine.websocket.message.payload;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/**
 * Payload for {@code conversation.attach} (Agent → Engine). The first
 * frame a bound worker sends on its freshly-opened, conversation-scoped
 * WebSocket after acting on {@code agent.bind}. Identifies the worker and
 * the conversation it is attaching for so the engine can register the
 * socket and flip the worker to {@code BOUND} (agent-concurrency §9.4).
 *
 * <p>The JWT that authenticates the socket travels in the WebSocket
 * handshake (query token), not in this body — the engine has already
 * pinned the worker's {@code agentInstanceId} from it by the time this
 * frame arrives.</p>
 */
@Data
@Builder
public class ConversationAttachPayload {

    /** The worker (agent instance) attaching its conversation socket. */
    private UUID agentId;

    /** The conversation this socket will carry turns + streamed output for. */
    private UUID conversationId;
}
