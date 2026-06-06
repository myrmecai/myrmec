package ai.myrmec.engine.websocket;

import ai.myrmec.engine.websocket.message.CloseCode;
import ai.myrmec.engine.websocket.message.MessageType;
import ai.myrmec.engine.websocket.message.WebSocketMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages WebSocket connections for agent instances.
 * Tracks connected agents, handles task assignment state, and maintains keep-alive.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentConnectionManager {
    
    private final ObjectMapper objectMapper;
    
    /** Map of agent instance ID to connection state */
    private final Map<UUID, AgentConnection> connections = new ConcurrentHashMap<>();
    
    /** Map of session ID to agent instance ID for reverse lookup */
    private final Map<String, UUID> sessionToAgent = new ConcurrentHashMap<>();
    
    /**
     * Register a new agent connection.
     * If agent already has a connection, close the old one with DUPLICATE_CONNECTION.
     */
    public void register(UUID agentInstanceId, String agentName, WebSocketSession session) {
        // Check for existing connection
        AgentConnection existing = connections.get(agentInstanceId);
        if (existing != null) {
            log.warn("Duplicate connection for agent instance {}, closing old session", agentInstanceId);
            closeSession(existing.getSession(), CloseCode.DUPLICATE_CONNECTION);
            sessionToAgent.remove(existing.getSession().getId());
        }
        
        AgentConnection connection = AgentConnection.builder()
                .agentInstanceId(agentInstanceId)
                .agentName(agentName)
                .session(session)
                .connectedAt(Instant.now())
                .lastPongAt(Instant.now())
                .build();
        
        connections.put(agentInstanceId, connection);
        sessionToAgent.put(session.getId(), agentInstanceId);
        
        log.info("Agent {} ({}) connected", agentName, agentInstanceId);
    }
    
    /**
     * Remove an agent connection.
     */
    public void unregister(WebSocketSession session) {
        UUID agentInstanceId = sessionToAgent.remove(session.getId());
        if (agentInstanceId != null) {
            AgentConnection connection = connections.remove(agentInstanceId);
            if (connection != null) {
                log.info("Agent {} ({}) disconnected", connection.getAgentName(), agentInstanceId);
            }
        }
    }
    
    /**
     * Get connection by agent instance ID.
     */
    public Optional<AgentConnection> getConnection(UUID agentInstanceId) {
        return Optional.ofNullable(connections.get(agentInstanceId));
    }
    
    /**
     * Get connection by session.
     */
    public Optional<AgentConnection> getConnectionBySession(WebSocketSession session) {
        UUID agentInstanceId = sessionToAgent.get(session.getId());
        if (agentInstanceId != null) {
            return Optional.ofNullable(connections.get(agentInstanceId));
        }
        return Optional.empty();
    }
    
    /**
     * Get all connected agents (read-only).
     */
    public Map<UUID, AgentConnection> getConnections() {
        return Collections.unmodifiableMap(connections);
    }
    
    /**
     * Get count of connected agents.
     */
    public int getConnectionCount() {
        return connections.size();
    }
    
    /**
     * Check if an agent is connected.
     */
    public boolean isConnected(UUID agentInstanceId) {
        return connections.containsKey(agentInstanceId);
    }

    /**
     * Check if an agent is idle (connected and not working on a task).
     */
    public boolean isAgentIdle(UUID agentInstanceId) {
        return getConnection(agentInstanceId)
                .map(AgentConnection::isIdle)
                .orElse(false);
    }
    
    /**
     * Record pong response from agent.
     */
    public void recordPong(WebSocketSession session) {
        getConnectionBySession(session).ifPresent(conn -> 
            conn.setLastPongAt(Instant.now())
        );
    }
    
    /**
     * Mark agent as working on a task.
     */
    public void assignTask(UUID agentInstanceId, UUID taskId) {
        getConnection(agentInstanceId).ifPresent(conn -> {
            conn.setCurrentTaskId(taskId);
            conn.setTaskAssignedAt(Instant.now());
        });
    }
    
    /**
     * Clear current task assignment.
     */
    public void clearTask(UUID agentInstanceId) {
        getConnection(agentInstanceId).ifPresent(conn -> {
            conn.setCurrentTaskId(null);
            conn.setTaskAssignedAt(null);
        });
    }
    
    /**
     * Send a message to an agent.
     */
    public boolean sendMessage(UUID agentInstanceId, WebSocketMessage<?> message) {
        return getConnection(agentInstanceId)
                .map(conn -> sendMessage(conn.getSession(), message))
                .orElse(false);
    }
    
    /**
     * Send a message through a session.
     */
    public boolean sendMessage(WebSocketSession session, WebSocketMessage<?> message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            session.sendMessage(new TextMessage(json));
            return true;
        } catch (IOException e) {
            log.error("Failed to send message to agent: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Send ping to all connected agents.
     * Called by scheduler every 30 seconds.
     */
    @Scheduled(fixedRate = 30_000)
    public void sendPingToAll() {
        if (connections.isEmpty()) {
            return;
        }
        
        log.debug("Sending ping to {} connected agents", connections.size());
        WebSocketMessage<Object> ping = WebSocketMessage.of(MessageType.PING, Map.of());
        
        connections.values().forEach(conn -> {
            if (conn.getSession().isOpen()) {
                sendMessage(conn.getSession(), ping);
            }
        });
    }
    
    /**
     * Check for dead connections (missed pongs).
     * Called by scheduler every 15 seconds.
     */
    @Scheduled(fixedRate = 15_000)
    public void checkDeadConnections() {
        if (connections.isEmpty()) {
            return;
        }
        
        Instant threshold = Instant.now().minusSeconds(70); // 2 missed pings + buffer
        
        connections.values().stream()
                .filter(conn -> conn.getLastPongAt().isBefore(threshold))
                .forEach(conn -> {
                    log.warn("Agent {} ({}) missed heartbeats, closing connection",
                            conn.getAgentName(), conn.getAgentInstanceId());
                    closeSession(conn.getSession(), CloseCode.GOING_AWAY);
                });
    }
    
    private void closeSession(WebSocketSession session, org.springframework.web.socket.CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException e) {
            log.debug("Error closing session: {}", e.getMessage());
        }
    }
}
