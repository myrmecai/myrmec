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
    private final AgentConversationWebSocketHandler agentConversationWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(agentWebSocketHandler, "/api/v1/agent/ws")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins("*"); // Configure properly for production

        // Slice 4c — conversation-scoped agent socket. A bound worker dials
        // its home node directly and opens this socket for one conversation
        // (agent-concurrency §9.4). Reuses the agent-control handshake
        // interceptor (same agent JWT pins the worker's instance id); the
        // conversation id arrives in the conversation.attach frame.
        registry.addHandler(agentConversationWebSocketHandler, "/api/v1/agent/conversation")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins("*");
    }
}
