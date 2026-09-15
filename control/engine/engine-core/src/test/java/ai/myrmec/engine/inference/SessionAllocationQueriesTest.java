// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.agent.AgentRepository;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 4 plumbing: pessimistic instance lock, per-instance worker counts,
 * and the allocation-state expiry queries the sweeper (Task 4) will drive.
 */
class SessionAllocationQueriesTest extends IntegrationTestBase {

    @Autowired TestDataBuilder data;
    @Autowired AgentHostInstanceRepository instances;
    @Autowired AgentRepository agentRepository;
    @Autowired SessionRepository sessionRepository;

    private AgentHostInstance openInstance() {
        AgentHostCreationResult created =
                data.agent().named("q-host").withMaxAgents(10).create();
        AgentHost host = created.agent();
        return instances.saveAndFlush(AgentHostInstance.open(
                host, null, UUID.randomUUID().toString(), "laptop", 4, Map.of(), "node-1"));
    }

    @Test
    @Transactional
    void lockQueryReturnsTheInstance() {
        AgentHostInstance instance = openInstance();

        AgentHostInstance locked = instances.findWithLockById(instance.getId()).orElseThrow();

        assertThat(locked.getId()).isEqualTo(instance.getId());
    }

    @Test
    void workerCountCountsOnlyTheGivenStatusesPerInstance() {
        AgentHostInstance instance = openInstance();
        Agent idle = worker(instance, Agent.Status.IDLE);
        worker(instance, Agent.Status.DEAD);

        long counted = agentRepository.countByAgentHostInstanceIdAndStatusIn(
                instance.getId(), List.of(Agent.Status.IDLE, Agent.Status.RESERVED));

        assertThat(counted).isEqualTo(1);
        assertThat(idle.getAgentHostInstanceId()).isEqualTo(instance.getId());
    }

    @Test
    void offerExpiryQueryFindsOnlyOfferedPastCutoff() {
        AgentHostInstance instance = openInstance();
        Project project = data.project().named("q-proj").create();

        Session offeredPast = session(project, instance, "OFFERED", Instant.now().minusSeconds(60));
        Session offeredFuture = session(project, instance, "OFFERED", Instant.now().plusSeconds(600));
        Session activePast = session(project, instance, "ACTIVE", Instant.now().minusSeconds(60));

        var results = sessionRepository.findByAllocationStateAndOfferExpiresAtBefore(
                "OFFERED", Instant.now());

        var ids = results.stream().map(Session::getId).toList();
        assertThat(ids).contains(offeredPast.getId());
        assertThat(ids).doesNotContain(offeredFuture.getId());
        assertThat(ids).doesNotContain(activePast.getId());
    }

    @Test
    void idleLeaseQueryFindsOnlyActiveSessionsPastExpiry() {
        AgentHostInstance instance = openInstance();
        Project project = data.project().named("q-proj2").create();

        Session activePast = session(project, instance, "ACTIVE", null);
        activePast.setIdleLeaseExpiresAt(Instant.now().minusSeconds(60));
        sessionRepository.saveAndFlush(activePast);
        Session activeFuture = session(project, instance, "ACTIVE", null);
        activeFuture.setIdleLeaseExpiresAt(Instant.now().plusSeconds(600));
        sessionRepository.saveAndFlush(activeFuture);

        var results = sessionRepository.findByAllocationStateAndIdleLeaseExpiresAtBefore(
                "ACTIVE", Instant.now());

        assertThat(results.stream().map(Session::getId)).contains(activePast.getId());
        assertThat(results.stream().map(Session::getId)).doesNotContain(activeFuture.getId());
    }

    private Agent worker(AgentHostInstance instance, Agent.Status status) {
        Agent agent = new Agent();
        agent.setAgentHostId(instance.getAgentHostId());
        agent.setAgentHostInstanceId(instance.getId());
        agent.setStatus(status);
        agent.setHostname("q-worker");
        agentRepository.saveAndFlush(agent);
        return agent;
    }

    private Session session(Project project, AgentHostInstance instance,
                            String allocationState, Instant offerExpiry) {
        Session session = new Session();
        session.setServiceType("CONVERSATION");
        session.setRefId(UUID.randomUUID());
        session.setProjectId(project.getId());
        session.setHostInstanceId(instance.getId());
        session.setAllocationState(allocationState);
        session.setOfferExpiresAt(offerExpiry);
        return sessionRepository.saveAndFlush(session);
    }
}
