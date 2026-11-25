// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.auth;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.testing.TestDataBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unified host registration endpoint (protocol §4.1): one route,
 * scheme-discriminated credentials. `Authorization: Key myr_agent_…` =
 * MANAGED; `Authorization: Bearer <user jwt>` = LOCAL (owner rides the
 * minted token's ownerUserId claim). No credential travels in the body.
 */
class HostAuthControllerTest extends IntegrationTestBase {

    @Autowired TestDataBuilder data;

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void keySchemeRegistersTheManagedHost() {
        AgentHostCreationResult result = data.agent().named("ep-managed").create();

        HttpHeaders headers = jsonHeaders();
        headers.set("Authorization", "Key " + result.registrationKey());

        String body = """
                { "hostname": "build-host-1", "runtimeVersion": "1.8.0" }
                """;

        var response = restTemplate.postForEntity(
                "/api/v1/agent/auth/host/register",
                new HttpEntity<>(body, headers), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("hostId")).isNotNull();
        assertThat(response.getBody().get("accessToken").asText()).isNotBlank();
        assertThat(response.getBody().get("refreshToken").asText()).isNotBlank();
        assertThat(response.getBody().get("hostType").asText()).isEqualTo("MANAGED");
        assertThat(response.getBody().hasNonNull("ownerUserId")).isFalse();
    }

    @Test
    void keySchemeWithUnknownKeyIsRejected() {
        HttpHeaders headers = jsonHeaders();
        headers.set("Authorization", "Key myr_agent_wrong");

        var response = restTemplate.postForEntity(
                "/api/v1/agent/auth/host/register",
                new HttpEntity<>("{ \"hostname\": \"h\" }", headers), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void bearerSchemeRegistersTheUsersLocalHost() {
        String body = """
                { "hostname": "developer-laptop", "runtimeVersion": "1.8.0" }
                """;

        var response = restTemplate.postForEntity(
                "/api/v1/agent/auth/host/register",
                new HttpEntity<>(body, adminHeaders()), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("hostId")).isNotNull();
        assertThat(response.getBody().get("hostType").asText()).isEqualTo("LOCAL");
        assertThat(response.getBody().get("ownerUserId").asText())
                .isEqualTo(TEST_ADMIN_ID.toString());
        assertThat(response.getBody().get("accessToken").asText()).isNotBlank();
    }

    @Test
    void bearerSchemeWithInvalidUserTokenIsRejected() {
        HttpHeaders headers = jsonHeaders();
        headers.set("Authorization", "Bearer not-a-jwt");

        var response = restTemplate.postForEntity(
                "/api/v1/agent/auth/host/register",
                new HttpEntity<>("{ \"hostname\": \"h\" }", headers), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void missingAuthorizationHeaderIsAValidationError() {
        var response = restTemplate.postForEntity(
                "/api/v1/agent/auth/host/register",
                new HttpEntity<>("{ \"hostname\": \"h\" }", jsonHeaders()), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void unsupportedSchemeIsRejected() {
        HttpHeaders headers = jsonHeaders();
        headers.set("Authorization", "Basic dXNlcjpwYXNz");

        var response = restTemplate.postForEntity(
                "/api/v1/agent/auth/host/register",
                new HttpEntity<>("{ \"hostname\": \"h\" }", headers), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }
}