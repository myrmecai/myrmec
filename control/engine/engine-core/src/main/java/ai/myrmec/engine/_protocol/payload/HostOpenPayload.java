// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine._protocol.payload;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import ai.myrmec.engine._protocol.SessionInteractionCapability;

/** host.open payload (§6.1). Owner identity is NOT here: the engine stamps
 * agent_host_instances.owner_user_id from the HOST token's ownerUserId claim
 * (resolved at the handshake) — ownership is token-verified, never
 * payload-asserted. */
public record HostOpenPayload(
        UUID instanceNonce,
        String hostname,
        String runtimeVersion,
        List<Integer> supportedProtocolVersions,
        int poolSize,
        Map<String, Object> capabilities,
        Map<String, Object> reportedCapacity) {

    /**
     * section 22.2: parse the typed session-interaction capability out of the
     * raw capabilities map. Current-contract validation: the block must be
     * present and exactly version 1 with temporaryHold=true — missing or
     * unsupported capability rejects host initialization (the caller turns
     * the thrown exception into protocol.error and mints no instance row).
     */
    public SessionInteractionCapability sessionInteractionCapability() {
        Object block = capabilities == null ? null : capabilities.get("sessionInteraction");
        if (block == null) {
            throw new IllegalArgumentException(
                    "host.open is missing the required sessionInteraction capability "
                            + "(section 22.2 current-contract validation)");
        }
        try {
            return SessionInteractionCapabilityParser.parse(block);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "host.open sessionInteraction capability rejected: " + e.getMessage());
        }
    }

    /** Strict parser for the capability block riding the raw Map wire shape. */
    static final class SessionInteractionCapabilityParser {
        private SessionInteractionCapabilityParser() {
        }

        static SessionInteractionCapability parse(Object block) {
            if (!(block instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException(
                        "sessionInteraction must be an object {version, temporaryHold}");
            }
            Object version = map.get("version");
            Object temporaryHold = map.get("temporaryHold");
            int parsedVersion;
            if (version instanceof Number n) {
                parsedVersion = n.intValue();
            } else if (version instanceof String s && s.chars().allMatch(Character::isDigit)) {
                parsedVersion = Integer.parseInt(s);
            } else {
                throw new IllegalArgumentException("version must be the integer 1");
            }
            if (!(temporaryHold instanceof Boolean b)) {
                throw new IllegalArgumentException("temporaryHold must be a boolean");
            }
            return new SessionInteractionCapability(parsedVersion, b);
        }
    }
}
