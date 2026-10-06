// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host;

import ai.myrmec.engine.websocket.message.CloseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
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
    /**
     * section 22.2: the capabilities each live instance advertised at
     * host.open — in-memory only (a reconnect re-advertises). Consulted by
     * the fail-closed host selection gate; a RECOVERING instance keeps its
     * entry so host.resume re-adoption restores it.
     */
    private final Map<UUID, Map<String, Object>> advertisedCapabilities =
            new ConcurrentHashMap<>();

    public void register(UUID hostInstanceId, WebSocketSession session) {
        register(hostInstanceId, session, null);
    }

    /**
     * Register a live socket with the capabilities the host advertised at
     * host.open (section 22.2). {@code capabilities} may be null on paths
     * that precede capability validation (resume re-adoption keeps the prior
     * entry).
     */
    public void register(UUID hostInstanceId, WebSocketSession session,
                         Map<String, Object> capabilities) {
        WebSocketSession existing = sessions.put(hostInstanceId, session);
        if (existing != null && existing.isOpen()) {
            log.warn("Duplicate host-instance connection {}; closing older socket", hostInstanceId);
            close(existing, CloseCode.DUPLICATE_CONNECTION);
            sessionToInstance.remove(existing.getId());
        }
        if (capabilities != null) {
            advertisedCapabilities.put(hostInstanceId, Map.copyOf(capabilities));
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

    /**
     * section 22.2: the capabilities advertised by this instance's current
     * connection (empty when unknown/dropped — callers fail closed).
     */
    public Map<String, Object> getAdvertisedCapabilities(UUID hostInstanceId) {
        return advertisedCapabilities.getOrDefault(hostInstanceId, Map.of());
    }

    public int size() {
        return sessions.size();
    }

    /**
     * Push an already-serialized frame to one host instance's control socket.
     * The relay path (§20) uses this: the frame crossed the mesh as an opaque
     * string and stays one — no typed-payload rebind on the receiving replica.
     *
     * @return {@code true} only when a live socket existed and accepted the send
     */
    public boolean sendRawMessage(UUID hostInstanceId, String frameJson) {
        WebSocketSession session = sessions.get(hostInstanceId);
        if (session == null || !session.isOpen()) {
            return false;
        }
        try {
            session.sendMessage(new TextMessage(frameJson));
            return true;
        } catch (IOException e) {
            log.warn("Failed relaying frame to host instance {}: {}", hostInstanceId, e.getMessage());
            return false;
        }
    }

    private void close(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception e) {
            log.debug("Failed closing duplicate host socket: {}", e.getMessage());
        }
    }
}
