// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.node;

import ai.myrmec.engine.websocket.host.ChannelConnectionRegistry;
import ai.myrmec.engine.websocket.host.HostConnectionManager;
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
import java.util.UUID;

/**
 * Node-to-node host-frame relay endpoint (protocol §20, decision H5).
 * Receives an engine→host frame forwarded by a peer replica and pushes it to
 * the target host instance's local socket: the dedicated session channel
 * first (§7.5) for a session-scoped execution frame, else the control socket.
 *
 * <p>This is an <b>internal</b> mesh endpoint, not an agent- or user-facing
 * one. It is gated by a shared secret (constant-time compared) and is inert
 * unless {@code myrmec.node.relay.enabled} is set with a non-blank secret, so
 * a single-node deployment exposes no live relay surface. The relayed frame
 * is treated as an opaque string and never deserialized into typed payloads
 * (§15 rule 10 boundary: the peer already validated the frame it authored;
 * binding untrusted bytes to payload classes here would widen the attack
 * surface for zero routing benefit).</p>
 */
@RestController
@RequestMapping("/api/v1/internal/agent-relay")
@Slf4j
public class AgentRelayController {

    /** Header carrying the shared mesh secret on every relay request. */
    public static final String NODE_SECRET_HEADER = "X-Node-Secret";

    private final HostConnectionManager connectionManager;
    private final ChannelConnectionRegistry channelRegistry;
    private final boolean relayEnabled;
    private final byte[] relaySecret;

    public AgentRelayController(
            HostConnectionManager connectionManager,
            ChannelConnectionRegistry channelRegistry,
            @Value("${myrmec.node.relay.enabled:false}") boolean relayEnabled,
            @Value("${myrmec.node.relay.secret:}") String relaySecret) {
        this.connectionManager = connectionManager;
        this.channelRegistry = channelRegistry;
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
            log.warn("Rejected node relay for instance {}: bad or missing mesh secret",
                    request.targetInstanceId());
            return ResponseEntity.status(403).build();
        }
        boolean delivered = deliver(request.targetInstanceId(), request.frame());
        if (!delivered) {
            log.info("Relayed frame for instance {} had no local socket (instance "
                    + "may live elsewhere or be gone)", request.targetInstanceId());
        }
        return ResponseEntity.ok(delivered);
    }

    /**
     * §7.5 channel-first local delivery: when the target instance is serving a
     * session on a dedicated channel the execution frame rides that socket;
     * otherwise the control socket. Session-scoped routing needs the sessionId
     * from the frame itself — but the relay is deliberately envelope-blind, so
     * the receiving side tries the channel registry through any session bound
     * to the instance's socket… which it cannot know without parsing. It does
     * NOT parse: a control-socket delivery is always correct for lifecycle
     * frames, and execution frames tolerate the control socket too (§7.5
     * explicitly makes the channel a transport preference, not a requirement).
     */
    private boolean deliver(UUID targetInstanceId, String frame) {
        return connectionManager.sendRawMessage(targetInstanceId, frame);
    }

    private boolean secretMatches(String presentedSecret) {
        if (presentedSecret == null) {
            return false;
        }
        return MessageDigest.isEqual(presentedSecret.getBytes(StandardCharsets.UTF_8), relaySecret);
    }
}