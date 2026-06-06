package ai.myrmec.engine.websocket;

import ai.myrmec.engine._system.security.JwtTokenProvider;
import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentInstance;
import ai.myrmec.engine.agent.AgentInstanceRepository;
import ai.myrmec.engine.agent.AgentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.UUID;

/**
 * Intercepts WebSocket handshake to validate JWT token and extract agent information.
 * Token is passed as query parameter: /api/v1/agent/ws?token=<accessToken>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtTokenProvider jwtTokenProvider;
    private final AgentInstanceRepository agentInstanceRepository;
    private final AgentRepository agentRepository;

    private static final String ATTR_AGENT_INSTANCE_ID = "agentInstanceId";
    private static final String ATTR_AGENT_NAME = "agentName";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {
        
        // Extract token from query parameter
        String token = extractToken(request);
        if (token == null || token.isBlank()) {
            log.warn("WebSocket handshake rejected: missing token");
            return false;
        }

        // Validate token
        if (!jwtTokenProvider.validateAccessToken(token)) {
            log.warn("WebSocket handshake rejected: invalid or expired token");
            return false;
        }

        // Verify it's an agent token
        if (!jwtTokenProvider.isAgentToken(token)) {
            log.warn("WebSocket handshake rejected: not an agent token");
            return false;
        }

        // Get agent instance ID from token subject
        UUID agentInstanceId = jwtTokenProvider.getSubjectId(token);

        // Verify agent instance exists and agent is active
        AgentInstance instance = agentInstanceRepository.findById(agentInstanceId).orElse(null);
        if (instance == null) {
            log.warn("WebSocket handshake rejected: agent instance {} not found", agentInstanceId);
            return false;
        }

        // Verify parent agent is active
        Agent agent = agentRepository.findById(instance.getAgentId()).orElse(null);
        if (agent == null || agent.getStatus() != Agent.Status.ACTIVE) {
            log.warn("WebSocket handshake rejected: agent {} is not active", instance.getAgentId());
            return false;
        }

        // Store agent info in session attributes
        attributes.put(ATTR_AGENT_INSTANCE_ID, agentInstanceId);
        attributes.put(ATTR_AGENT_NAME, agent.getName());

        log.debug("WebSocket handshake approved for agent {} (instance {})", 
                agent.getName(), agentInstanceId);
        
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        if (exception != null) {
            log.error("WebSocket handshake error: {}", exception.getMessage());
        }
    }

    private String extractToken(ServerHttpRequest request) {
        try {
            return UriComponentsBuilder.fromUri(request.getURI())
                    .build()
                    .getQueryParams()
                    .getFirst("token");
        } catch (Exception e) {
            log.debug("Failed to extract token from request: {}", e.getMessage());
            return null;
        }
    }
}
