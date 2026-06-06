package ai.myrmec.engine.websocket.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Base WebSocket message format for agent communication.
 * All messages follow this structure with type-specific payloads.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebSocketMessage<T> {
    
    private String type;
    private Instant timestamp;
    private T payload;
    
    public static <T> WebSocketMessage<T> of(String type, T payload) {
        return WebSocketMessage.<T>builder()
                .type(type)
                .timestamp(Instant.now())
                .payload(payload)
                .build();
    }
}
