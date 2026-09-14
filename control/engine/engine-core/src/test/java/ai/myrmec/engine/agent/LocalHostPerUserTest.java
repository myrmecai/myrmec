// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Local hosts are per-USER, not per-(user, project) (design 2026-09-11 §1.3;
 * protocol §4.1 local registration resolves "the user's local host"). The
 * durable record carries hostType=LOCAL + ownerUserId; is_local/local_user_id
 * are gone. Admin/seed-created hosts default to MANAGED.
 */
class LocalHostPerUserTest extends IntegrationTestBase {

    @Autowired TestDataBuilder data;
    @Autowired AgentHostService agentHostService;
    @Autowired AgentHostRepository agentHostRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private static final UUID LOCAL_DEFAULT_PROFILE_ID =
            UUID.fromString("6d7b8c9d-0e1f-4a2b-8c3d-9e4f5a6b7c8d");

    private void seedLocalDefaultProfile() {
        jdbcTemplate.update(
                "INSERT INTO agent_profiles (id, name, description, is_system, addendum_allowed, status) "
                + "VALUES (?, ?, ?, TRUE, TRUE, 'ACTIVE')",
                LOCAL_DEFAULT_PROFILE_ID, "local-default", "Seeded default local agent profile (test re-seed)");
    }

    @Test
    void builderCreatedHostsDefaultToManaged() {
        AgentProfile profile = data.agentProfile().named("m-profile").create();
        AgentHostCreationResult result = data.agent().named("m-host").withProfile(profile).create();

        AgentHost host = agentHostRepository.findById(result.agent().getId()).orElseThrow();
        assertThat(host.getHostType()).isEqualTo(AgentHostType.MANAGED);
        assertThat(host.getOwnerUserId()).isNull();
    }

    @Test
    void localHostIsPerUserNotPerProject() {
        seedLocalDefaultProfile();
        UUID user = UUID.randomUUID();
        // Real projects (fk_agent_hosts_project rejects random UUIDs); two
        // DISTINCT projects prove the lookup is user-scoped, not
        // project-scoped — a per-(user, project) regression would create a
        // second host and fail the assertion below.
        ai.myrmec.engine.project.Project projectA = data.project().named("lh-a").create();
        ai.myrmec.engine.project.Project projectB = data.project().named("lh-b").create();

        AgentHost first = agentHostService.upsertLocalAgentHost(user, projectA.getId(), "laptop-a");
        AgentHost second = agentHostService.upsertLocalAgentHost(user, projectB.getId(), "laptop-b");

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(first.getHostType()).isEqualTo(AgentHostType.LOCAL);
        assertThat(first.getOwnerUserId()).isEqualTo(user);
    }

    @Test
    void distinctUsersGetDistinctLocalHosts() {
        seedLocalDefaultProfile();
        AgentHost userOne = agentHostService.upsertLocalAgentHost(UUID.randomUUID(), null, "laptop");
        AgentHost userTwo = agentHostService.upsertLocalAgentHost(UUID.randomUUID(), null, "laptop");

        assertThat(userOne.getId()).isNotEqualTo(userTwo.getId());
        assertThat(userOne.getHostType()).isEqualTo(AgentHostType.LOCAL);
        assertThat(userTwo.getHostType()).isEqualTo(AgentHostType.LOCAL);
    }
}
