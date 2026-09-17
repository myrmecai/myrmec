// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live channel-socket registry (protocol §7.5): sessionId → dedicated session
 * socket. A binding is advisory transport preference — losing the channel
 * NEVER closes the session (§7.5); the engine falls back to the control
 * socket. A re-open replaces any stale binding.
 */
@Slf4j
@Component
public class ChannelConnectionRegistry {

    private final Map<UUID, WebSocketSession> channels = new ConcurrentHashMap<>();

    /** Bind (or re-bind) the dedicated socket for a session. */
    public void register(UUID sessionId, WebSocketSession channelSocket) {
        WebSocketSession previous = channels.put(sessionId, channelSocket);
        if (previous != null && previous.isOpen()) {
            log.debug("Channel re-opened for session {} — replacing stale socket", sessionId);
        }
    }

    /** Unregister only if this socket is the one currently bound. */
    public void unregister(UUID sessionId, WebSocketSession channelSocket) {
        channels.remove(sessionId, channelSocket);
    }

    /** The dedicated channel socket for a session, when bound and open. */
    public Optional<WebSocketSession> getChannel(UUID sessionId) {
        WebSocketSession socket = channels.get(sessionId);
        if (socket == null || !socket.isOpen()) {
            return Optional.empty();
        }
        return Optional.of(socket);
    }

    /** Current bound-channel count (test/ops visibility). */
    public int size() {
        return channels.size();
    }
}