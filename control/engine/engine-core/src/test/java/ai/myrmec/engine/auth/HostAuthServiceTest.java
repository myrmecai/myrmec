// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.auth;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine._system.exception.InvalidRegistrationKeyException;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.agent.AgentHostRepository;
import ai.myrmec.engine.agent.AgentHostType;
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
    @Autowired AgentHostRepository agentHostRepository;
    @Autowired AgentRepository agentRepository;
    @Autowired TestDataBuilder data;
    @Autowired JdbcTemplate jdbcTemplate;

    /**
     * The 017 default-local-profile seed is dropped (changeset 024) and
     * Plan 8 T5 removed the engine's fallback to it — registerLocal never
     * touches agent_profiles. Assert no seeded default remains and create
     * the standard TestDataBuilder profile so the test exercises the
     * post-decoupling semantics with a real profile fixture present.
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

    /**
     * Local-owner model (§4.1): only MANAGED hosts consume registration
     * keys. A LOCAL host's key is a dormant placeholder — presenting it to
     * the managed path is rejected.
     */
    @Test
    void managedRegistrationRejectsALocalHostKey() {
        HostRegisterResponse local = hostAuthService.registerLocal(
                TEST_ADMIN_ID, new HostLocalRegisterRequest(), "dev-laptop");
        AgentHost localHost = agentHostRepository.findById(local.getHostId()).orElseThrow();
        assertThat(localHost.getHostType()).isEqualTo(AgentHostType.LOCAL);

        HostRegisterRequest managedPath = new HostRegisterRequest();
        managedPath.setRegistrationKey(localHost.getRegistrationKey());
        managedPath.setHostname("dev-laptop");
        managedPath.setRuntimeVersion("1.8.0");

        assertThatThrownBy(() -> hostAuthService.registerManaged(managedPath))
                .isInstanceOf(InvalidRegistrationKeyException.class)
                .hasMessageContaining("LOCAL");
    }

    @Test
    void localRegistrationResolvesOrCreatesTheUsersHost() {
        // Changeset 024 dropped the 017 seed: no default local profile exists.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_profiles WHERE id = ?",
                Long.class, DEFAULT_LOCAL_PROFILE_ID)).isZero();
        // Standard fixture profile proves registration is independent of any
        // seeded default (host-profile decoupling, Plan 8 T5).
        data.agentProfile().named("local-reg-profile").create();

        HostLocalRegisterRequest request = new HostLocalRegisterRequest();
        request.setHostname("developer-laptop");
        request.setRuntimeVersion("1.8.0");

        HostRegisterResponse response = hostAuthService.registerLocal(TEST_ADMIN_ID, request, "developer-laptop");

        assertThat(response.getHostId()).isNotNull();
        assertThat(response.getAccessToken()).isNotBlank();
        assertThat(response.getRefreshToken()).isNotBlank();
    }
}