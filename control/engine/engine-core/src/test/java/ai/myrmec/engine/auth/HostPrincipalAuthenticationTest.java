// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.auth;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine._system.security.AgentHostPrincipal;
import ai.myrmec.engine._system.security.JwtAuthenticationFilter;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class HostPrincipalAuthenticationTest extends IntegrationTestBase {

    @Autowired TestDataBuilder data;
    @Autowired JwtAuthenticationFilter jwtAuthenticationFilter;
    @Autowired TransactionTemplate transactionTemplate;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void hostJwtAuthenticatesAsRoleAgentHost() {
        AgentHostCreationResult result =
                data.agent().named("filter-host").create();
        String token = jwtTokenProvider.generateHostAccessToken(
                result.agent().getId(), result.agent().getName());

        Authentication authentication = runFilterWithBearer(token);

        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_AGENT_HOST");
        assertThat(authentication.getPrincipal()).isInstanceOf(AgentHostPrincipal.class);
        assertThat(((AgentHostPrincipal) authentication.getPrincipal()).getHostId())
                .isEqualTo(result.agent().getId());
    }

    @Test
    void inactiveHostTokenIsNotAuthenticated() {
        AgentHostCreationResult result =
                data.agent().named("filter-inactive-host").create();

        // Deactivate in a committed transaction so the filter (and any other
        // thread) sees the change.
        transactionTemplate.executeWithoutResult(status -> {
            AgentHost host = agentHostRepository.findById(result.agent().getId()).orElseThrow();
            host.setStatus(AgentHost.Status.INACTIVE);
        });

        String token = jwtTokenProvider.generateHostAccessToken(
                result.agent().getId(), result.agent().getName());

        // The filter must NOT authenticate an inactive host: the security
        // context stays empty.
        assertThat(runFilterWithBearer(token)).isNull();
    }

    /**
     * Run the real filter against a mocked request/response and return the
     * authentication it installed in the SecurityContext (null if it did not
     * authenticate). Asserts the filter's direct effect — discriminating
     * between "recognized as ROLE_AGENT_HOST" and "declined".
     */
    private Authentication runFilterWithBearer(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/anything");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            jwtAuthenticationFilter.doFilter(request, response, new MockFilterChain());
        } catch (Exception e) {
            throw new AssertionError("Filter threw", e);
        }
        return SecurityContextHolder.getContext().getAuthentication();
    }
}
