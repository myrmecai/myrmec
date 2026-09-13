// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.auth;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine._system.security.AgentHostPrincipal;
import ai.myrmec.engine._system.security.JwtTokenProvider;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class HostTokenClaimsTest extends IntegrationTestBase {

    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TestDataBuilder data;

    @Test
    void hostAccessTokenCarriesAgentHostPrincipalAndSubject() {
        AgentProfile profile = data.agentProfile().named("host-claims-profile").create();
        AgentHost host = data.agent().named("host-claims").withProfile(profile).create().agent();

        String token = jwtTokenProvider.generateHostAccessToken(host.getId(), host.getName());

        assertThat(jwtTokenProvider.validateAccessToken(token)).isTrue();
        assertThat(jwtTokenProvider.isAgentHostToken(token)).isTrue();
        assertThat(jwtTokenProvider.isAgentToken(token)).isFalse();
        assertThat(jwtTokenProvider.isUserToken(token)).isFalse();
        assertThat(jwtTokenProvider.getSubjectId(token)).isEqualTo(host.getId());
        assertThat(jwtTokenProvider.getName(token)).isEqualTo(host.getName());
    }

    @Test
    void hostRefreshTokenIsSeparateTypeAndSubject() {
        AgentProfile profile = data.agentProfile().named("host-claims-refresh-profile").create();
        AgentHost host = data.agent().named("host-claims-refresh").withProfile(profile).create().agent();

        String token = jwtTokenProvider.generateHostRefreshToken(host.getId());

        assertThat(jwtTokenProvider.validateRefreshToken(token)).isTrue();
        assertThat(jwtTokenProvider.isAgentHostToken(token)).isTrue();
        assertThat(jwtTokenProvider.getSubjectId(token)).isEqualTo(host.getId());
    }

    @Test
    void principalWrapsDurableHostEntity() {
        AgentProfile profile = data.agentProfile().named("host-claims-principal-profile").create();
        AgentHost host = data.agent().named("host-claims-principal").withProfile(profile).create().agent();
        AgentHostPrincipal principal = new AgentHostPrincipal(host, host.getName());

        assertThat(principal.getHostId()).isEqualTo(host.getId());
        assertThat(principal.getHost()).isSameAs(host);
        assertThat(principal.getName()).isEqualTo(host.getName());
    }
}