// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.auth;

import ai.myrmec.engine._system.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Refresh-token family state for the AGENT_HOST principal (unified protocol
 * §4.1). Rotation is on-use: each refresh mints a NEW refresh token and marks
 * the presented one consumed. Presenting a consumed token is replay — it
 * revokes the whole family (protocol: "token reuse revokes the
 * refresh-token family"). Deactivation/key revocation revokes via the same
 * family set.
 *
 * <p>In-memory for v1 (single-node engine); §20 leaves multi-replica state
 * replication to the engine's existing inter-node mechanisms.</p>
 */
@Component
@RequiredArgsConstructor
public class HostRefreshTokenStore {

    private final JwtTokenProvider jwtTokenProvider;

    private record TokenState(UUID hostId, boolean consumed) {}

    /** token string -> its state. */
    private final Map<String, TokenState> tokens = new ConcurrentHashMap<>();
    /** hostId -> every token ever issued for that host (the family). */
    private final Map<UUID, List<String>> families = new ConcurrentHashMap<>();

    /** Issue a fresh refresh token for the host and record it in the family. */
    public String issue(UUID hostId) {
        String token = jwtTokenProvider.generateHostRefreshToken(hostId);
        tokens.put(token, new TokenState(hostId, false));
        families.computeIfAbsent(hostId, k -> new CopyOnWriteArrayList<>()).add(token);
        return token;
    }
}