// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.agent.AgentHostRepository;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Host-principal handshake (§4.1/§4.2/§15): only an AGENT_HOST token whose
 * subject resolves to an ACTIVE agent_hosts row passes. AGENT and USER
 * tokens are rejected — the §18 "Token isolation" row deferred from Plan 1.
 */
class HostControlHandshakeTest extends IntegrationTestBase {

    @Autowired HostControlHandshakeInterceptor interceptor;
    @Autowired TestDataBuilder data;
    @Autowired AgentHostRepository agentHostRepository;
    @Autowired TransactionTemplate transactionTemplate;

    private record Handshake(boolean accepted, Map<String, Object> attrs) {}

    private Handshake attempt(String uri) {
        String query = "";
        String path = uri;
        int q = uri.indexOf('?');
        if (q >= 0) {
            path = uri.substring(0, q);
            query = uri.substring(q + 1);
        }
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", path);
        servletRequest.setQueryString(query);
        if (!query.isEmpty()) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    servletRequest.addParameter(pair.substring(0, eq), pair.substring(eq + 1));
                }
            }
        }
        ServerHttpRequest request = new ServletServerHttpRequest(servletRequest);
        Map<String, Object> attrs = new HashMap<>();
        boolean ok = interceptor.beforeHandshake(
                request, new ServletServerHttpResponse(new MockHttpServletResponse()), null, attrs);
        return new Handshake(ok, attrs);
    }

    private AgentHost activeHost() {
        AgentProfile profile = data.agentProfile().named("hs-profile").create();
        return data.agent().named("hs-host").withProfile(profile).create().agent();
    }

    @Test
    void hostTokenIsAcceptedAndPinsHostAttributes() {
        AgentHost host = activeHost();
        String token = jwtTokenProvider.generateHostAccessToken(host.getId(), host.getName());

        Handshake result = attempt(
                "/api/v1/agent/host/ws?token=" + token);

        assertThat(result.accepted()).isTrue();
        assertThat(result.attrs().get(HostControlHandshakeInterceptor.ATTR_HOST_ID))
                .isEqualTo(host.getId());
        assertThat(result.attrs().get(HostControlHandshakeInterceptor.ATTR_HOST_NAME))
                .isEqualTo(host.getName());
    }

    @Test
    void legacyAgentTokenIsRejected() {
        // An AGENT-principal token — even one whose subject is a real host id —
        // must never pass the host handshake (principal claim is AGENT).
        AgentHost host = activeHost();
        String agentToken = jwtTokenProvider.generateAgentAccessToken(host.getId(), host.getName());

        assertThat(attempt("/api/v1/agent/host/ws?token=" + agentToken).accepted()).isFalse();
    }

    @Test
    void userTokenIsRejected() {
        String userToken = jwtTokenProvider.generateUserAccessToken(
                TEST_ADMIN_ID, TEST_ADMIN_NAME, TEST_ADMIN_EMAIL, java.util.List.of("sys:PLATFORM_ADMIN"));

        assertThat(attempt("/api/v1/agent/host/ws?token=" + userToken).accepted()).isFalse();
    }

    @Test
    void missingAndGarbageTokensAreRejected() {
        assertThat(attempt("/api/v1/agent/host/ws").accepted()).isFalse();
        assertThat(attempt("/api/v1/agent/host/ws?token=not-a-jwt").accepted()).isFalse();
    }

    @Test
    void inactiveHostIsRejected() {
        AgentHost host = activeHost();
        UUID hostId = host.getId();
        transactionTemplate.executeWithoutResult(status -> {
            AgentHost managed = agentHostRepository.findById(hostId).orElseThrow();
            managed.setStatus(AgentHost.Status.INACTIVE);
        });
        String token = jwtTokenProvider.generateHostAccessToken(hostId, host.getName());

        assertThat(attempt("/api/v1/agent/host/ws?token=" + token).accepted()).isFalse();
    }

    @Test
    void tokenForUnknownHostIsRejected() {
        String token = jwtTokenProvider.generateHostAccessToken(UUID.randomUUID(), "ghost");

        assertThat(attempt("/api/v1/agent/host/ws?token=" + token).accepted()).isFalse();
    }
}
