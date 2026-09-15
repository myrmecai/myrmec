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

class HostAuthControllerTest extends IntegrationTestBase {

    @Autowired TestDataBuilder data;

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void managedHostRegistrationEndpointReturns200AndTokens() {
        AgentHostCreationResult result = data.agent().named("ep-managed").create();

        String body = """
                {
                  "registrationKey": "%s",
                  "hostname": "build-host-1",
                  "runtimeVersion": "1.8.0"
                }
                """.formatted(result.registrationKey());

        var response = restTemplate.postForEntity(
                "/api/v1/agent/auth/host/register",
                new HttpEntity<>(body, jsonHeaders()), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("hostId")).isNotNull();
        assertThat(response.getBody().get("accessToken").asText()).isNotBlank();
        assertThat(response.getBody().get("refreshToken").asText()).isNotBlank();
    }

    @Test
    void localHostRegistrationEndpointAcceptsAdminUser() {
        String body = """
                {
                  "hostname": "developer-laptop",
                  "runtimeVersion": "1.8.0"
                }
                """;

        var response = restTemplate.postForEntity(
                "/api/v1/agent/auth/host/local/register",
                new HttpEntity<>(body, adminHeaders()), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("hostId")).isNotNull();
    }

    @Test
    void localHostRegistrationWithoutUserTokenIsRejected() {
        String body = """
                {
                  "hostname": "developer-laptop",
                  "runtimeVersion": "1.8.0"
                }
                """;

        var response = restTemplate.postForEntity(
                "/api/v1/agent/auth/host/local/register",
                new HttpEntity<>(body, jsonHeaders()), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }
}
