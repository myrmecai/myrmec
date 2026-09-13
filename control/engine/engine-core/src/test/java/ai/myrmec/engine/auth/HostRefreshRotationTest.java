// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.auth;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine._system.exception.InvalidTokenException;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.auth.dto.HostRefreshRequest;
import ai.myrmec.engine.auth.dto.HostRefreshResponse;
import ai.myrmec.engine.auth.dto.HostRegisterRequest;
import ai.myrmec.engine.auth.dto.HostRegisterResponse;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HostRefreshRotationTest extends IntegrationTestBase {

    @Autowired HostAuthService hostAuthService;
    @Autowired TestDataBuilder data;

    @Test
    void rotationReturnsNewPairAndRejectsReplayOfOldToken() {
        AgentProfile profile = data.agentProfile().named("rot-profile").create();
        AgentHostCreationResult result = data.agent().named("rot-host").withProfile(profile).create();
        String first = firstRefreshTokenOf(result);

        HostRefreshResponse pair = rotate(first);

        assertThat(pair.getAccessToken()).isNotBlank();
        assertThat(pair.getRefreshToken()).isNotBlank();
        assertThat(pair.getRefreshToken()).isNotEqualTo(first);

        // Protocol §18: "Rotation returns a new pair; replay or revocation
        // rejects the token family"
        assertThatThrownBy(() -> rotate(first))
                .isInstanceOf(InvalidTokenException.class);
        // And the family is dead: the NEW token is revoked too
        assertThatThrownBy(() -> rotate(pair.getRefreshToken()))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void deactivatedHostRefreshIsRejected() {
        AgentProfile profile = data.agentProfile().named("deact-profile").create();
        AgentHostCreationResult result = data.agent().named("deact-host").withProfile(profile).create();
        String refresh = firstRefreshTokenOf(result);

        result.agent().setStatus(AgentHost.Status.INACTIVE);
        agentHostRepository.save(result.agent());

        assertThatThrownBy(() -> rotate(refresh))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void legacyAgentRefreshTokenIsRejectedOnHostEndpoint() {
        AgentProfile profile = data.agentProfile().named("agent-token-profile").create();
        AgentHostCreationResult result = data.agent().named("agent-token-host").withProfile(profile).create();
        // A legacy AGENT-principal refresh token whose subject is (wrongly)
        // the host id — must be rejected because the principal claim is AGENT.
        String agentInstanceToken = jwtTokenProvider.generateAgentRefreshToken(result.agent().getId());

        HostRefreshRequest request = new HostRefreshRequest();
        request.setRefreshToken(agentInstanceToken);

        assertThatThrownBy(() -> hostAuthService.refresh(request))
                .isInstanceOf(InvalidTokenException.class);
    }

    private String firstRefreshTokenOf(AgentHostCreationResult result) {
        return register(result).getRefreshToken();
    }

    private HostRegisterResponse register(AgentHostCreationResult result) {
        HostRegisterRequest request = new HostRegisterRequest();
        request.setRegistrationKey(result.registrationKey());
        request.setHostname("rot-host");
        request.setRuntimeVersion("1.8.0");
        return hostAuthService.registerManaged(request);
    }

    private HostRefreshResponse rotate(String refreshToken) {
        HostRefreshRequest request = new HostRefreshRequest();
        request.setRefreshToken(refreshToken);
        return hostAuthService.refresh(request);
    }
}
