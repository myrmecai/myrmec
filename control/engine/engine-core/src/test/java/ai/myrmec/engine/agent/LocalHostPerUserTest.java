// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Local hosts are per-USER, not per-(user, project) (design 2026-09-11 §1.3;
 * protocol §4.1 local registration resolves "the user's local host"). The
 * durable host row is a placeholder (hostType=LOCAL, owner column dropped) —
 * the ONLY owner record is agent_host_instances.owner_user_id, stamped at
 * host.open. Matching rides the live instance. Admin/seed-created hosts
 * default to MANAGED.
 */
class LocalHostPerUserTest extends IntegrationTestBase {

    @Autowired TestDataBuilder data;
    @Autowired AgentHostService agentHostService;
    @Autowired AgentHostRepository agentHostRepository;
    @Autowired AgentHostInstanceRepository agentHostInstanceRepository;

    @Test
    void builderCreatedHostsDefaultToManaged() {
        AgentHostCreationResult result = data.agent().named("m-host").create();

        AgentHost host = agentHostRepository.findById(result.agent().getId()).orElseThrow();
        assertThat(host.getHostType()).isEqualTo(AgentHostType.MANAGED);
    }

    @Test
    void localHostIsPerUserNotPerProject() {
        UUID user = UUID.randomUUID();
        // Real projects (fk_agent_hosts_project rejects random UUIDs); two
        // DISTINCT projects prove the lookup is user-scoped, not
        // project-scoped — a per-(user, project) regression would create a
        // second host and fail the assertion below.
        ai.myrmec.engine.project.Project projectA = data.project().named("lh-a").create();
        ai.myrmec.engine.project.Project projectB = data.project().named("lh-b").create();

        AgentHost first = agentHostService.upsertLocalAgentHost(user, projectA.getId(), "laptop-a");
        // host.open stamps the live instance with the owner (the ONLY owner
        // record; the host row is a placeholder).
        agentHostInstanceRepository.saveAndFlush(AgentHostInstance.open(
                agentHostRepository.findById(first.getId()).orElseThrow(),
                user, UUID.randomUUID().toString(), "laptop-a", 1, null, "node-a"));
        AgentHost second = agentHostService.upsertLocalAgentHost(user, projectB.getId(), "laptop-b");

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(first.getHostType()).isEqualTo(AgentHostType.LOCAL);
    }

    @Test
    void distinctUsersGetDistinctLocalHosts() {
        AgentHost userOne = agentHostService.upsertLocalAgentHost(UUID.randomUUID(), null, "laptop");
        AgentHost userTwo = agentHostService.upsertLocalAgentHost(UUID.randomUUID(), null, "laptop");

        assertThat(userOne.getId()).isNotEqualTo(userTwo.getId());
        assertThat(userOne.getHostType()).isEqualTo(AgentHostType.LOCAL);
        assertThat(userTwo.getHostType()).isEqualTo(AgentHostType.LOCAL);
    }

    /**
     * Local-owner model (§3.7/§4.1): matching rides the live INSTANCE.
     * Without an OPEN owned instance every registration mints a fresh host
     * (the host column is gone); once the plugin's host.open stamps an OPEN
     * instance with the owner, the SAME host is reused.
     */
    @Test
    void reuseMatchesByLiveInstanceOwner() {
        UUID user = UUID.randomUUID();
        AgentHost created = agentHostService.upsertLocalAgentHost(user, null, "laptop-1");

        // No live instance yet — a second registration cannot match; the
        // name-collision suffix keeps the fresh host insertable.
        AgentHost again = agentHostService.upsertLocalAgentHost(user, null, "laptop-1");
        assertThat(again.getId()).isNotEqualTo(created.getId());

        // host.open stamps the OPEN instance with the owner → same host reused.
        agentHostInstanceRepository.saveAndFlush(AgentHostInstance.open(
                agentHostRepository.findById(created.getId()).orElseThrow(),
                user, UUID.randomUUID().toString(), "laptop-1", 1, null, "node-a"));

        AgentHost reused = agentHostService.upsertLocalAgentHost(user, null, "laptop-1");
        assertThat(reused.getId()).isEqualTo(created.getId());
    }
}
