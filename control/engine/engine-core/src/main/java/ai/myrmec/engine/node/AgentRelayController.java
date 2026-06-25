package ai.myrmec.engine.node;

import ai.myrmec.engine.websocket.AgentConnectionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Node-to-node relay endpoint (slice 4b, agent-concurrency §9.8). Receives a
 * control frame forwarded by a peer replica and pushes it to the target
 * worker's local socket.
 *
 * <p>This is an <b>internal</b> mesh endpoint, not an agent- or user-facing
 * one. It is gated by a shared secret (constant-time compared) and is inert
 * unless {@code myrmec.node.relay.enabled} is set with a non-blank secret, so a
 * single-node OSS deployment exposes no live relay surface. The relayed frame
 * is treated as an opaque string and never deserialized into typed payloads.</p>
 */
@RestController
@RequestMapping("/api/v1/internal/agent-relay")
@Slf4j
public class AgentRelayController {

    /** Header carrying the shared mesh secret on every relay request. */
    public static final String NODE_SECRET_HEADER = "X-Node-Secret";

    private final AgentConnectionManager connectionManager;
    private final boolean relayEnabled;
    private final byte[] relaySecret;

    public AgentRelayController(
            AgentConnectionManager connectionManager,
            @Value("${myrmec.node.relay.enabled:false}") boolean relayEnabled,
            @Value("${myrmec.node.relay.secret:}") String relaySecret) {
        this.connectionManager = connectionManager;
        this.relayEnabled = relayEnabled;
        this.relaySecret = relaySecret.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping
    public ResponseEntity<Boolean> relay(
            @RequestHeader(value = NODE_SECRET_HEADER, required = false) String presentedSecret,
            @RequestBody AgentRelayRequest request) {
        if (!relayEnabled || relaySecret.length == 0) {
            log.warn("Rejected node relay: relay is disabled on this replica");
            return ResponseEntity.status(403).build();
        }
        if (!secretMatches(presentedSecret)) {
            log.warn("Rejected node relay for worker {}: bad or missing mesh secret",
                    request.agentInstanceId());
            return ResponseEntity.status(403).build();
        }
        boolean delivered = connectionManager.sendRawMessage(request.agentInstanceId(), request.frameJson());
        return ResponseEntity.ok(delivered);
    }

    private boolean secretMatches(String presentedSecret) {
        if (presentedSecret == null) {
            return false;
        }
        return MessageDigest.isEqual(presentedSecret.getBytes(StandardCharsets.UTF_8), relaySecret);
    }
}
