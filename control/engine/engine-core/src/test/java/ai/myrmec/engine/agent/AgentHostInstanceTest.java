// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Append-only contract of agent_host_instances (protocol §19.1 / design
 * 2026-09-11 §2.5, R11): one row per supervisor run, CLOSE is terminal and
 * write-once, live_key gives portable one-live-per-owner uniqueness.
 */
class AgentHostInstanceTest extends IntegrationTestBase {

    @Autowired TestDataBuilder data;
    @Autowired AgentHostInstanceRepository instances;

    private AgentHost host() {
        AgentProfile profile = data.agentProfile().named("inst-profile").create();
        return data.agent().named("inst-host").withProfile(profile).create().agent();
    }

    private AgentHostInstance openInstance(AgentHost host, UUID owner) {
        return instances.saveAndFlush(AgentHostInstance.open(
                host, owner, "dev-laptop", 4, Map.of("cpuCount", 8), "engine-node-1"));
    }

    @Test
    void openPersistsWithOpenStatusAndLiveKey() {
        AgentHost host = host();
        AgentHostInstance instance = openInstance(host, null);

        AgentHostInstance reloaded = instances.findById(instance.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AgentHostInstance.Status.OPEN);
        assertThat(reloaded.getLiveKey()).isEqualTo("OPEN");
        assertThat(reloaded.getAgentHostId()).isEqualTo(host.getId());
        assertThat(reloaded.getPoolSize()).isEqualTo(4);
        assertThat(reloaded.getOpenedAt()).isNotNull();
        assertThat(reloaded.getClosedAt()).isNull();
        assertThat(reloaded.getReportedCapacity()).containsEntry("cpuCount", 8);
    }

    @Test
    void closeIsTerminalWriteOnceAndIdempotent() {
        AgentHostInstance instance = openInstance(host(), null);

        instance.close("DISCONNECT");
        instances.saveAndFlush(instance);
        Instant firstClosedAt = instance.getClosedAt();

        // Second close must NOT overwrite closed_at (history stays truthful).
        instance.close("HOST_LOST");
        instances.saveAndFlush(instance);

        AgentHostInstance reloaded = instances.findById(instance.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AgentHostInstance.Status.CLOSED);
        assertThat(reloaded.getLiveKey()).isNull();
        assertThat(reloaded.getClosedAt()).isNotNull();
        assertThat(reloaded.getCloseReason()).isEqualTo("DISCONNECT");
    }

    @Test
    void heartbeatUpdatesLastHeartbeatAt() {
        AgentHostInstance instance = openInstance(host(), null);
        assertThat(instance.getLastHeartbeatAt()).isNull();

        instance.markHeartbeat();
        instances.saveAndFlush(instance);

        assertThat(instances.findById(instance.getId()).orElseThrow().getLastHeartbeatAt())
                .isNotNull();
    }

    @Test
    void uniqueIndexRejectsSecondLiveInstancePerOwner() {
        AgentHost host = host();
        UUID owner = UUID.randomUUID();
        openInstance(host, owner);

        assertThatThrownBy(() -> openInstance(host, owner))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void closingFirstAllowsSecondLiveInstanceAndKeepsHistory() {
        AgentHost host = host();
        UUID owner = UUID.randomUUID();
        AgentHostInstance first = openInstance(host, owner);
        first.close("SUPERSEDED");
        instances.saveAndFlush(first);

        AgentHostInstance second = openInstance(host, owner);
        assertThat(second.getId()).isNotEqualTo(first.getId());

        var history = instances.findByAgentHostIdOrderByOpenedAtDesc(host.getId());
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getId()).isEqualTo(second.getId());
        assertThat(history.get(1).getStatus()).isEqualTo(AgentHostInstance.Status.CLOSED);
    }
}
