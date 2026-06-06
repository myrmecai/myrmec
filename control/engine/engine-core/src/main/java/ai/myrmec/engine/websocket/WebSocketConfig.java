package ai.myrmec.engine.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket configuration for agent communication.
 * Registers the agent WebSocket handler at /api/v1/agent/ws
 */
@Configuration
@EnableWebSocket
@EnableScheduling
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final AgentWebSocketHandler agentWebSocketHandler;
    private final AgentWebSocketHandshakeInterceptor handshakeInterceptor;
    private final UserConversationWebSocketHandler userConversationWebSocketHandler;
    private final UserConversationHandshakeInterceptor userConversationHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(agentWebSocketHandler, "/api/v1/agent/ws")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins("*"); // Configure properly for production

        // Phase 6c-2 — user-facing live stream. Path includes {id}; the
        // handshake interceptor parses it out of the URI and pins both
        // the conversation id and authenticated user id into the session
        // attributes.
        registry.addHandler(userConversationWebSocketHandler,
                        "/api/v1/conversations/*/stream")
                .addInterceptors(userConversationHandshakeInterceptor)
                .setAllowedOrigins("*");
    }
}
