// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.auth;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine._system.security.AgentHostPrincipal;
import ai.myrmec.engine._system.security.JwtTokenProvider;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HostTokenClaimsTest extends IntegrationTestBase {

    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TestDataBuilder data;

    @Test
    void hostAccessTokenCarriesAgentHostPrincipalAndSubject() {
        AgentHost host = data.agent().named("host-claims").create().agent();

        String token = jwtTokenProvider.generateHostAccessToken(host.getId(), host.getName(), null);

        assertThat(jwtTokenProvider.validateAccessToken(token)).isTrue();
        assertThat(jwtTokenProvider.isAgentHostToken(token)).isTrue();
        assertThat(jwtTokenProvider.isAgentToken(token)).isFalse();
        assertThat(jwtTokenProvider.isUserToken(token)).isFalse();
        assertThat(jwtTokenProvider.getSubjectId(token)).isEqualTo(host.getId());
        assertThat(jwtTokenProvider.getName(token)).isEqualTo(host.getName());
        // MANAGED tokens carry no owner identity.
        assertThat(jwtTokenProvider.getOwnerUserId(token)).isNull();
    }

    @Test
    void localHostAccessTokenCarriesOwnerClaimAndIssuer() {
        AgentHost host = data.agent().named("host-claims-owner").create().agent();
        UUID owner = UUID.randomUUID();

        // LOCAL-minted token: the ownerUserId claim stamps instance ownership.
        String token = jwtTokenProvider.generateHostAccessToken(host.getId(), host.getName(), owner);

        assertThat(jwtTokenProvider.validateAccessToken(token)).isTrue();
        assertThat(jwtTokenProvider.getOwnerUserId(token)).isEqualTo(owner);
    }

    @Test
    void everyMintedTokenCarriesTheIssuer() {
        AgentHost host = data.agent().named("host-claims-issuer").create().agent();
        String issuer = jwtTokenProvider.getIssuer();

        // The issuer is configured and stamped into every minted flavour
        // (parsing the compact form's payload header is enough to see iss).
        assertThat(issuer).isNotBlank();

        java.util.function.BiConsumer<String, String> assertIssuer = (token, flavour) -> {
            String[] parts = token.split("\\.");
            String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertThat(payload).as(flavour).contains("\"iss\":\"" + issuer + "\"");
        };

        assertIssuer.accept(jwtTokenProvider.generateHostAccessToken(host.getId(), host.getName(), null), "host access");
        assertIssuer.accept(jwtTokenProvider.generateHostRefreshToken(host.getId()), "host refresh");
        assertIssuer.accept(jwtTokenProvider.generateAgentAccessToken(host.getId(), host.getName()), "agent access");
        assertIssuer.accept(jwtTokenProvider.generateUserAccessToken(
                UUID.randomUUID(), "U", "u@example.com", java.util.List.of("sys:EDITOR")), "user access");
        assertIssuer.accept(jwtTokenProvider.generateUserRefreshToken(UUID.randomUUID()), "user refresh");
    }

    @Test
    void hostRefreshTokenIsSeparateTypeAndSubject() {
        AgentHost host = data.agent().named("host-claims-refresh").create().agent();

        String token = jwtTokenProvider.generateHostRefreshToken(host.getId());

        assertThat(jwtTokenProvider.validateRefreshToken(token)).isTrue();
        assertThat(jwtTokenProvider.isAgentHostToken(token)).isTrue();
        assertThat(jwtTokenProvider.getSubjectId(token)).isEqualTo(host.getId());
    }

    @Test
    void principalWrapsDurableHostEntity() {
        AgentHost host = data.agent().named("host-claims-principal").create().agent();
        AgentHostPrincipal principal = new AgentHostPrincipal(host, host.getName());

        assertThat(principal.getHostId()).isEqualTo(host.getId());
        assertThat(principal.getHost()).isSameAs(host);
        assertThat(principal.getName()).isEqualTo(host.getName());
    }
}