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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HostAuthControllerTest extends IntegrationTestBase {

    @Autowired TestDataBuilder data;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    // Fixtures discovered in Tasks 1–2 (both REQUIRED, tests fail without them):
    // (b) The local-register endpoint calls registerLocal with null profileId,
    //     which falls back to the Liquibase-017 seeded default local profile
    //     (fixed id 6d7b8c9d-0e1f-4a2b-8c3d-9e4f5a6b7c8d) — but the base class's
    //     per-test cleanup wipes agent_profiles, so the row must be re-seeded
    //     with a native JdbcTemplate INSERT (JPA save regenerates the assigned
    //     id due to @GeneratedValue). Seed it at the top of the local-register
    //     test. Exact statement verified green in Task 2:
    //     jdbcTemplate.update("INSERT INTO agent_profiles (id, name, description, is_system, addendum_allowed, status) VALUES (?, ?, ?, TRUE, TRUE, 'ACTIVE')",
    //             java.util.UUID.fromString("6d7b8c9d-0e1f-4a2b-8c3d-9e4f5a6b7c8d"), "local-default", "Seeded default local agent profile (test re-seed)");
    private final UUID LOCAL_DEFAULT_PROFILE_ID =
            UUID.fromString("6d7b8c9d-0e1f-4a2b-8c3d-9e4f5a6b7c8d");

    private void seedLocalDefaultProfile() {
        jdbcTemplate.update(
                "INSERT INTO agent_profiles (id, name, description, is_system, addendum_allowed, status) "
                + "VALUES (?, ?, ?, TRUE, TRUE, 'ACTIVE')",
                LOCAL_DEFAULT_PROFILE_ID, "local-default", "Seeded default local agent profile (test re-seed)");
    }

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
        seedLocalDefaultProfile();
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
