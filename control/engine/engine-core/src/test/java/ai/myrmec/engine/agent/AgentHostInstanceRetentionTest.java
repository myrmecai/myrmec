// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design §3.2a: closed instance history is retained but bounded — the sweep
 * deletes only CLOSED rows past the cutoff. OPEN rows are NEVER deleted
 * regardless of age, and a swept instance's agents reference is nulled, not
 * deleted (FK SET NULL).
 */
class AgentHostInstanceRetentionTest extends IntegrationTestBase {

    @Autowired TestDataBuilder data;
    @Autowired AgentHostInstanceRepository instances;
    @Autowired AgentHostInstanceRetentionSweeper sweeper;
    @Autowired AgentRepository agentRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private AgentHost host() {
        return data.agent().named("ret-host").create().agent();
    }

    private AgentHostInstance closedInstance(AgentHost host) {
        AgentHostInstance instance = instances.saveAndFlush(AgentHostInstance.open(
                host, UUID.randomUUID(), "laptop", 1, Map.of(), "engine-node-1"));
        instance.close("DISCONNECT");
        instances.saveAndFlush(instance);
        return instance;
    }

    private void ageClosedAt(UUID instanceId, int daysAgo) {
        jdbcTemplate.update(
                "UPDATE agent_host_instances SET closed_at = ? WHERE id = ?",
                Instant.now().minusSeconds((long) daysAgo * 86400), instanceId);
    }

    @Test
    void sweepDeletesOnlyClosedRowsPastCutoff() {
        AgentHost host = host();
        AgentHostInstance oldClosed = closedInstance(host);
        AgentHostInstance recentClosed = closedInstance(host);
        AgentHostInstance stillOpen = instances.saveAndFlush(AgentHostInstance.open(
                host, UUID.randomUUID(), "laptop", 1, Map.of(), "engine-node-1"));
        ageClosedAt(oldClosed.getId(), 40);
        ageClosedAt(recentClosed.getId(), 1);
        // An OPEN row older than the cutoff must survive — backdate opened_at.
        jdbcTemplate.update(
                "UPDATE agent_host_instances SET opened_at = ? WHERE id = ?",
                Instant.now().minusSeconds(40L * 86400), stillOpen.getId());

        int deleted = sweeper.sweep(Instant.now());

        assertThat(deleted).isEqualTo(1);
        assertThat(instances.existsById(oldClosed.getId())).isFalse();
        assertThat(instances.existsById(recentClosed.getId())).isTrue();
        assertThat(instances.existsById(stillOpen.getId())).isTrue();
        assertThat(instances.findById(stillOpen.getId()).orElseThrow().getStatus())
                .isEqualTo(AgentHostInstance.Status.OPEN);
    }

    @Test
    void sweepNullsReferencingAgentRowsInsteadOfDeletingThem() {
        AgentHost host = host();
        AgentHostInstance oldClosed = closedInstance(host);
        ageClosedAt(oldClosed.getId(), 40);

        Agent worker = new Agent();
        worker.setAgentHostId(host.getId());
        worker.setAgentHostInstanceId(oldClosed.getId());
        worker.setStatus(Agent.Status.DEAD);
        worker.setHostname("laptop");
        worker = agentRepository.saveAndFlush(worker);

        sweeper.sweep(Instant.now());

        assertThat(instances.existsById(oldClosed.getId())).isFalse();
        Agent reloaded = agentRepository.findById(worker.getId()).orElseThrow();
        assertThat(reloaded.getAgentHostInstanceId()).isNull();
        assertThat(reloaded.getAgentHostId()).isEqualTo(host.getId());
    }
}
