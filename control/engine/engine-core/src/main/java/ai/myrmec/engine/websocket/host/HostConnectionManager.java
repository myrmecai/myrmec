// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host;

import ai.myrmec.engine.websocket.message.CloseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live control-socket registry for host instances (§4.2): one authenticated
 * connection per hostInstanceId. A duplicate connection for the same
 * instance closes the older socket with DUPLICATE_CONNECTION, mirroring the
 * legacy AgentConnectionManager's policy.
 */
@Slf4j
@Component
public class HostConnectionManager {

    private final Map<UUID, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, UUID> sessionToInstance = new ConcurrentHashMap<>();

    public void register(UUID hostInstanceId, WebSocketSession session) {
        WebSocketSession existing = sessions.put(hostInstanceId, session);
        if (existing != null && existing.isOpen()) {
            log.warn("Duplicate host-instance connection {}; closing older socket", hostInstanceId);
            close(existing, CloseCode.DUPLICATE_CONNECTION);
            sessionToInstance.remove(existing.getId());
        }
        sessionToInstance.put(session.getId(), hostInstanceId);
    }

    public void unregister(WebSocketSession session) {
        UUID instanceId = sessionToInstance.remove(session.getId());
        if (instanceId != null) {
            sessions.remove(instanceId, session);
        }
    }

    public Optional<WebSocketSession> getSession(UUID hostInstanceId) {
        return Optional.ofNullable(sessions.get(hostInstanceId));
    }

    public int size() {
        return sessions.size();
    }

    private void close(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception e) {
            log.debug("Failed closing duplicate host socket: {}", e.getMessage());
        }
    }
}
