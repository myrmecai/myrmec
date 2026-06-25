package ai.myrmec.engine.conversation.stream;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;

/**
 * {@link ConversationSubscriber} backed by an {@link SseEmitter}.
 *
 * <p>Each connected browser tab opens one SSE stream
 * ({@code GET /api/v1/conversations/{id}/stream}); the controller wraps
 * the emitter in one of these and registers it with the
 * {@link ConversationStreamBroker}. Frames are sent under the default
 * {@code message} event so the client reads them via
 * {@code EventSource.onmessage} and parses the same {@code {type,payload}}
 * envelope the WebSocket transport used to send.</p>
 */
public class SseConversationSubscriber implements ConversationSubscriber {

    private final String id = UUID.randomUUID().toString();
    private final SseEmitter emitter;
    private volatile boolean open = true;

    public SseConversationSubscriber(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void send(String jsonFrame) throws IOException {
        try {
            emitter.send(SseEmitter.event().data(jsonFrame));
        } catch (IOException e) {
            open = false;
            throw e;
        } catch (IllegalStateException e) {
            // Emitter already completed/timed out — treat as a dead sink so
            // the broker drops it. Wrap so callers see a single failure type.
            open = false;
            throw new IOException("SSE emitter is closed", e);
        }
    }

    /** Mark the sink dead once the emitter has completed/timed out/errored. */
    public void markClosed() {
        open = false;
    }
}
