// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.auth;

import ai.myrmec.engine._system.exception.InvalidTokenException;
import ai.myrmec.engine._system.security.JwtTokenProvider;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostRepository;
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
    private final AgentHostRepository agentHostRepository;

    private record TokenState(UUID hostId, boolean consumed) {}

    /** token string -> its state. */
    private final Map<String, TokenState> tokens = new ConcurrentHashMap<>();
    /** hostId -> every token ever issued for that host (the family). */
    private final Map<UUID, List<String>> families = new ConcurrentHashMap<>();

    public record TokenPair(String accessToken, String refreshToken) {}

    /** Issue a fresh refresh token for the host and record it in the family. */
    public String issue(UUID hostId) {
        String token = jwtTokenProvider.generateHostRefreshToken(hostId);
        tokens.put(token, new TokenState(hostId, false));
        families.computeIfAbsent(hostId, k -> new CopyOnWriteArrayList<>()).add(token);
        return token;
    }

    /**
     * Rotate: present a live refresh token, receive a fresh pair. Replay of a
     * consumed token revokes the family. Deactivated hosts are rejected and
     * their families revoked.
     */
    public TokenPair rotate(String presentedRefreshToken) {
        TokenState state = tokens.get(presentedRefreshToken);
        if (state == null) {
            // Unknown token: nothing to revoke, reject outright.
            throw new InvalidTokenException("Unknown or expired refresh token");
        }
        if (state.consumed()) {
            revokeFamily(state.hostId());
            throw new InvalidTokenException("Refresh token replay detected — family revoked");
        }

        UUID hostId = state.hostId();
        AgentHost host = agentHostRepository.findById(hostId)
                .orElseThrow(() -> new InvalidTokenException("Agent host not found"));
        if (host.getStatus() != AgentHost.Status.ACTIVE) {
            revokeFamily(hostId);
            throw new InvalidTokenException("Host is not active");
        }

        String accessToken = jwtTokenProvider.generateHostAccessToken(hostId, host.getName());
        String nextRefresh = jwtTokenProvider.generateHostRefreshToken(hostId);
        tokens.put(presentedRefreshToken, new TokenState(hostId, true));
        tokens.put(nextRefresh, new TokenState(hostId, false));
        families.computeIfAbsent(hostId, k -> new CopyOnWriteArrayList<>()).add(nextRefresh);
        return new TokenPair(accessToken, nextRefresh);
    }

    /** Revoke every token ever issued for the host. */
    private void revokeFamily(UUID hostId) {
        List<String> family = families.get(hostId);
        if (family != null) {
            family.forEach(tokens::remove);
            families.remove(hostId);
        }
    }
}