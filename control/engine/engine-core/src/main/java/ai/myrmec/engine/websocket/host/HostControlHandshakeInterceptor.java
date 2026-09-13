// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host;

import ai.myrmec.engine._system.security.JwtTokenProvider;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostRepository;
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
 * Host-control socket handshake (protocol §4.2): authenticates the HOST
 * principal only. The token subject is a durable agent_hosts.id — the entity
 * is resolved and must be ACTIVE; hostType/ownership are never read from
 * claims. AGENT (instance) and USER tokens are rejected: this is the §18
 * "Token isolation" gate. It does NOT create a host instance — the first
 * valid host.open does (§4.2).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HostControlHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_HOST_ID = "hostId";
    public static final String ATTR_HOST_NAME = "hostName";

    private final JwtTokenProvider jwtTokenProvider;
    private final AgentHostRepository agentHostRepository;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = extractToken(request);
        if (token == null || token.isBlank()) {
            log.warn("Host WS handshake rejected: missing token");
            return false;
        }
        if (!jwtTokenProvider.validateAccessToken(token)) {
            log.warn("Host WS handshake rejected: invalid or expired token");
            return false;
        }
        if (!jwtTokenProvider.isAgentHostToken(token)) {
            // AGENT/USER principals can never authenticate the host socket.
            log.warn("Host WS handshake rejected: not an AGENT_HOST token");
            return false;
        }

        UUID hostId = jwtTokenProvider.getSubjectId(token);
        AgentHost host = agentHostRepository.findById(hostId).orElse(null);
        if (host == null || host.getStatus() != AgentHost.Status.ACTIVE) {
            log.warn("Host WS handshake rejected: host {} not found or not active", hostId);
            return false;
        }

        attributes.put(ATTR_HOST_ID, host.getId());
        attributes.put(ATTR_HOST_NAME, host.getName());
        log.debug("Host WS handshake approved for host {} ({})", host.getId(), host.getName());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        if (exception != null) {
            log.error("Host WS handshake error: {}", exception.getMessage());
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
