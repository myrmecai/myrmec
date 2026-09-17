package ai.myrmec.engine.websocket;

import ai.myrmec.engine.websocket.host.HostControlHandshakeInterceptor;
import ai.myrmec.engine.websocket.host.HostChannelWebSocketHandler;
import ai.myrmec.engine.websocket.host.HostControlWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket configuration for the unified host-control protocol. The legacy
 * agent wire ({@code /api/v1/agent/ws}, the conversation socket) is deleted —
 * hosts dial {@code /api/v1/agent/host/ws}.
 */
@Configuration
@EnableWebSocket
@EnableScheduling
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final HostControlWebSocketHandler hostControlWebSocketHandler;
    private final HostChannelWebSocketHandler hostChannelWebSocketHandler;
    private final HostControlHandshakeInterceptor hostControlHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Unified protocol §4.2 — host-principal control socket; auth is the
        // HOST_JWT validated in the handshake interceptor. The live instance
        // is created by the first host.open, not by the connection.
        registry.addHandler(hostControlWebSocketHandler, "/api/v1/agent/host/ws")
                .addInterceptors(hostControlHandshakeInterceptor)
                .setAllowedOrigins("*");
        // Unified protocol §7.5 — the optional dedicated session channel;
        // same HOST_JWT handshake gate, the single-use channel token rides
        // the channel.open payload.
        registry.addHandler(hostChannelWebSocketHandler, "/api/v1/agent/host/ws/channel")
                .addInterceptors(hostControlHandshakeInterceptor)
                .setAllowedOrigins("*");
    }
}
