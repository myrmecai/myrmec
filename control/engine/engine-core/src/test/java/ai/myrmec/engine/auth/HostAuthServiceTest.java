// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.auth;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine._system.exception.InvalidRegistrationKeyException;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.agent.AgentRepository;
import ai.myrmec.engine.auth.dto.HostLocalRegisterRequest;
import ai.myrmec.engine.auth.dto.HostRegisterRequest;
import ai.myrmec.engine.auth.dto.HostRegisterResponse;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HostAuthServiceTest extends IntegrationTestBase {

    @Autowired HostAuthService hostAuthService;
    @Autowired AgentRepository agentRepository;
    @Autowired TestDataBuilder data;
    @Autowired JdbcTemplate jdbcTemplate;

    /**
     * The fixed id AgentHostService falls back to when registerLocal passes
     * a null profileId (Liquibase 017 seed; AgentProfile uses
     * @GeneratedValue so the row cannot be seeded via the repository — the
     * assigned id would be regenerated). Native insert preserves the id.
     */
    private static final UUID DEFAULT_LOCAL_PROFILE_ID =
            UUID.fromString("6d7b8c9d-0e1f-4a2b-8c3d-9e4f5a6b7c8d");

    @Test
    void managedRegistrationReturnsHostTokensWithoutCreatingAgentSlot() {
        AgentHostCreationResult result = data.agent().named("managed-host").create();

        HostRegisterRequest request = new HostRegisterRequest();
        request.setRegistrationKey(result.registrationKey());
        request.setHostname("build-host-1");
        request.setRuntimeVersion("1.8.0");

        HostRegisterResponse response = hostAuthService.registerManaged(request);

        assertThat(response.getHostId()).isEqualTo(result.agent().getId());
        assertThat(response.getAccessToken()).isNotBlank();
        assertThat(response.getRefreshToken()).isNotBlank();
        // Protocol §18: "Valid registration key returns host tokens without
        // creating an Agent slot"
        assertThat(agentRepository.countByAgentHostId(response.getHostId())).isZero();
    }

    @Test
    void managedRegistrationRejectsInactiveHost() {
        AgentHostCreationResult result = data.agent().named("inactive-host").create();
        result.agent().setStatus(AgentHost.Status.INACTIVE);
        agentHostRepository.save(result.agent());

        HostRegisterRequest request = new HostRegisterRequest();
        request.setRegistrationKey(result.registrationKey());
        request.setHostname("build-host-2");
        request.setRuntimeVersion("1.8.0");

        assertThatThrownBy(() -> hostAuthService.registerManaged(request))
                .isInstanceOf(InvalidRegistrationKeyException.class);
    }

    @Test
    void localRegistrationResolvesOrCreatesTheUsersHost() {
        // Fixture: reproduce the Liquibase 017 seed — the default local agent
        // profile with the fixed id AgentHostService falls back to — because
        // IntegrationTestBase's per-test cleanup wipes all agent_profiles.
        jdbcTemplate.update(
                "INSERT INTO agent_profiles (id, name, description, is_system, "
                        + "addendum_allowed, status, created_at) "
                        + "VALUES (?, ?, ?, TRUE, TRUE, 'ACTIVE', CURRENT_TIMESTAMP)",
                DEFAULT_LOCAL_PROFILE_ID,
                "Myrmec Local Agent",
                "Built-in profile for the local IDE agent");

        HostLocalRegisterRequest request = new HostLocalRegisterRequest();
        request.setHostname("developer-laptop");
        request.setRuntimeVersion("1.8.0");

        HostRegisterResponse response = hostAuthService.registerLocal(TEST_ADMIN_ID, request, "developer-laptop");

        assertThat(response.getHostId()).isNotNull();
        assertThat(response.getAccessToken()).isNotBlank();
        assertThat(response.getRefreshToken()).isNotBlank();
        // The local host is per user — upsertLocalAgentHost reuses it.
        HostRegisterResponse second = hostAuthService.registerLocal(TEST_ADMIN_ID, request, "developer-laptop");
        assertThat(second.getHostId()).isEqualTo(response.getHostId());
    }
}