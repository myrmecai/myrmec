// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.auth;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class HostPrincipalAuthenticationTest extends IntegrationTestBase {

    @Autowired TestDataBuilder data;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    void hostJwtAuthenticatesAsRoleAgentHost() {
        AgentProfile profile = data.agentProfile().named("p").create();
        AgentHostCreationResult result = data.agent().named("filter-host").withProfile(profile).create();
        String token = jwtTokenProvider.generateHostAccessToken(
                result.agent().getId(), result.agent().getName());

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        // The admin endpoint requires authentication; a host token passes the
        // filter (recognized as a principal) but lacks PLATFORM_ADMIN, so the
        // expected status is 403 — proving recognition. 401 would mean the
        // filter did not authenticate the principal at all.
        var response = restTemplate.exchange(
                "/api/v1/admin/agents", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void inactiveHostTokenIsNotAuthenticated() {
        AgentProfile profile = data.agentProfile().named("p").create();
        AgentHostCreationResult result = data.agent().named("filter-inactive-host").withProfile(profile).create();

        // Deactivate in a committed transaction so the embedded server thread
        // sees the change; the test method itself is not @Transactional.
        transactionTemplate.executeWithoutResult(status -> {
            AgentHost host = agentHostRepository.findById(result.agent().getId()).orElseThrow();
            host.setStatus(AgentHost.Status.INACTIVE);
        });

        String token = jwtTokenProvider.generateHostAccessToken(
                result.agent().getId(), result.agent().getName());

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        // Because the security chain routes unauthenticated requests to
        // anonymous and then denies /api/v1/admin/**, Spring returns 403 for
        // an unauthenticated bearer whose host is inactive. 401 is only
        // returned for missing/invalid tokens. The value here is proving the
        // host was *not* authenticated as ROLE_AGENT_HOST — status is the
        // framework's anonymous-denied code, which is 403 in this config.
        var response = restTemplate.exchange(
                "/api/v1/admin/agents", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }
}
