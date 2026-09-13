// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.auth;

import ai.myrmec.engine._system.exception.InvalidRegistrationKeyException;
import ai.myrmec.engine._system.exception.InvalidTokenException;
import ai.myrmec.engine._system.security.JwtTokenProvider;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostRepository;
import ai.myrmec.engine.agent.AgentHostService;
import ai.myrmec.engine.auth.dto.HostLocalRegisterRequest;
import ai.myrmec.engine.auth.dto.HostRefreshRequest;
import ai.myrmec.engine.auth.dto.HostRefreshResponse;
import ai.myrmec.engine.auth.dto.HostRegisterRequest;
import ai.myrmec.engine.auth.dto.HostRegisterResponse;
import ai.myrmec.engine.registration.RegistrationKeyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class HostAuthService {

    private final AgentHostRepository agentHostRepository;
    private final AgentHostService agentHostService;
    private final RegistrationKeyService registrationKeyService;
    private final JwtTokenProvider jwtTokenProvider;
    private final HostRefreshTokenStore hostRefreshTokenStore;

    /**
     * Managed-host registration (protocol §4.1). Validates the durable host
     * definition via its registration key, mints the HOST_JWT/refresh pair —
     * and deliberately creates NO agents row (no execution slot) and NO
     * host-instance row. The live instance is created later by host.open.
     */
    @Transactional
    public HostRegisterResponse registerManaged(HostRegisterRequest request) {
        AgentHost host = agentHostRepository.findByRegistrationKey(request.getRegistrationKey())
                .orElseThrow(() -> new InvalidRegistrationKeyException("Invalid registration key"));

        if (host.getStatus() != AgentHost.Status.ACTIVE) {
            throw new InvalidRegistrationKeyException("Agent host definition is not active");
        }

        registrationKeyService.findByKeyValue(request.getRegistrationKey())
                .filter(key -> !key.isValid())
                .ifPresent(key -> {
                    throw new InvalidRegistrationKeyException("Registration key is expired or revoked");
                });

        return mintPair(host, "managed");
    }

    /**
     * Local-host registration (protocol §4.1). The caller (controller) has
     * already authenticated the user; ownership comes from the user id and
     * is stored on the durable host record, never in the token.
     */
    @Transactional
    public HostRegisterResponse registerLocal(UUID userId, HostLocalRegisterRequest request, String hostname) {
        if (userId == null) {
            throw new InvalidRegistrationKeyException("userId is required for local host registration");
        }
        AgentHost host = agentHostService.upsertLocalAgentHost(userId, request.getProjectId(), null, hostname);
        return mintPair(host, "local");
    }

    private HostRegisterResponse mintPair(AgentHost host, String flavour) {
        String accessToken = jwtTokenProvider.generateHostAccessToken(host.getId(), host.getName());
        String refreshToken = hostRefreshTokenStore.issue(host.getId());
        log.info("Host principal registered ({}): host {} ({})", flavour, host.getId(), host.getName());
        return HostRegisterResponse.builder()
                .hostId(host.getId())
                .accessToken(accessToken)
                .accessTokenExpiresAt(jwtTokenProvider.getExpiration(accessToken))
                .refreshToken(refreshToken)
                .refreshTokenExpiresAt(jwtTokenProvider.getExpiration(refreshToken))
                .build();
    }

    /**
     * Rotate a host refresh token (protocol §4.1). Only AGENT_HOST-principal
     * refresh tokens are accepted — a legacy AGENT instance token is
     * rejected (protocol §18 "Token isolation").
     */
    @Transactional
    public HostRefreshResponse refresh(HostRefreshRequest request) {
        String refreshToken = request.getRefreshToken();

        if (!jwtTokenProvider.validateRefreshToken(refreshToken)) {
            throw new InvalidTokenException("Invalid or expired refresh token");
        }
        if (!jwtTokenProvider.isAgentHostToken(refreshToken)) {
            throw new InvalidTokenException("Invalid token type for host refresh");
        }

        HostRefreshTokenStore.TokenPair pair = hostRefreshTokenStore.rotate(refreshToken);

        return HostRefreshResponse.builder()
                .accessToken(pair.accessToken())
                .accessTokenExpiresAt(jwtTokenProvider.getExpiration(pair.accessToken()))
                .refreshToken(pair.refreshToken())
                .refreshTokenExpiresAt(jwtTokenProvider.getExpiration(pair.refreshToken()))
                .build();
    }
}