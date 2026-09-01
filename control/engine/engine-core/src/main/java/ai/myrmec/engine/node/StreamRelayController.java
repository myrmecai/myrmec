// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.node;

import ai.myrmec.engine.conversation.stream.ConversationStreamBroker;
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
 * Node-to-node stream relay endpoint. Receives a streaming frame forwarded
 * by a peer replica and delivers it to local SSE subscribers via the
 * {@link ConversationStreamBroker}.
 *
 * <p>This is an <b>internal</b> mesh endpoint, gated by the same shared
 * secret as {@link AgentRelayController}. It is inert unless
 * {@code myrmec.node.relay.enabled} is set with a non-blank secret.</p>
 */
@RestController
@RequestMapping("/api/v1/internal/stream-relay")
@Slf4j
public class StreamRelayController {

    private final ConversationStreamBroker broker;
    private final boolean relayEnabled;
    private final byte[] relaySecret;

    public StreamRelayController(
            ConversationStreamBroker broker,
            @Value("${myrmec.node.relay.enabled:false}") boolean relayEnabled,
            @Value("${myrmec.node.relay.secret:}") String relaySecret) {
        this.broker = broker;
        this.relayEnabled = relayEnabled;
        this.relaySecret = relaySecret.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping
    public ResponseEntity<Boolean> relay(
            @RequestHeader(value = AgentRelayController.NODE_SECRET_HEADER, required = false) String presentedSecret,
            @RequestBody StreamRelayRequest request) {
        if (!relayEnabled || relaySecret.length == 0) {
            log.warn("Rejected stream relay: relay is disabled on this replica");
            return ResponseEntity.status(403).build();
        }
        if (!secretMatches(presentedSecret)) {
            log.warn("Rejected stream relay for conv {}: bad or missing mesh secret",
                    request.conversationId());
            return ResponseEntity.status(403).build();
        }
        broker.deliverRemote(request.conversationId(), request.frameJson());
        return ResponseEntity.ok(true);
    }

    private boolean secretMatches(String presentedSecret) {
        if (presentedSecret == null) {
            return false;
        }
        return MessageDigest.isEqual(presentedSecret.getBytes(StandardCharsets.UTF_8), relaySecret);
    }
}