// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Protocol §19.1 decision 2: the existing sessions table is extended (kind,
 * host_instance_id, allocation state, lease fields) and agents gains its
 * instance FK (ON DELETE SET NULL — the retention sweep must null, not
 * delete, work records).
 */
class SchemaAdditiveColumnsTest extends IntegrationTestBase {

    @Autowired TestDataBuilder data;
    @Autowired AgentRepository agentRepository;
    @Autowired AgentHostInstanceRepository instances;
    @Autowired SessionRepository sessionRepository;

    private Project project() {
        return data.project().named("schema-additive").create();
    }

    private AgentHostCreationResult host() {
        AgentProfile profile = data.agentProfile().named("add-profile").create();
        return data.agent().named("add-host").withProfile(profile).create();
    }

    @Test
    void sessionPersistsProtocolFields() {
        AgentHost host = host().agent();
        AgentHostInstance instance = instances.saveAndFlush(AgentHostInstance.open(
                host, null, "laptop", 2, Map.of(), "engine-node-1"));

        Session session = new Session();
        session.setServiceType("CONVERSATION");
        UUID refId = UUID.randomUUID();
        session.setRefId(refId);
        session.setProjectId(project().getId());
        session.setKind("CONVERSATION");
        session.setHostInstanceId(instance.getId());
        session.setAllocationState("OFFERED");
        Instant offerExpiry = Instant.now().plusSeconds(10).truncatedTo(ChronoUnit.MILLIS);
        session.setOfferExpiresAt(offerExpiry);
        session.setIdleLeaseExpiresAt(Instant.now().plusSeconds(1800));
        sessionRepository.saveAndFlush(session);

        Session reloaded = sessionRepository.findById(session.getId()).orElseThrow();
        assertThat(reloaded.getKind()).isEqualTo("CONVERSATION");
        assertThat(reloaded.getHostInstanceId()).isEqualTo(instance.getId());
        assertThat(reloaded.getAllocationState()).isEqualTo("OFFERED");
        assertThat(reloaded.getOfferExpiresAt()).isEqualTo(offerExpiry);
        assertThat(reloaded.getIdleLeaseExpiresAt()).isNotNull();
        // Existing semantics untouched.
        assertThat(reloaded.getRefId()).isEqualTo(refId);
        assertThat(reloaded.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void agentInstanceFkIsNullWhenInstanceDeleted() {
        AgentHost host = host().agent();
        AgentHostInstance instance = instances.saveAndFlush(AgentHostInstance.open(
                host, null, "laptop", 2, Map.of(), "engine-node-1"));

        Agent worker = new Agent();
        worker.setAgentHostId(host.getId());
        worker.setAgentHostInstanceId(instance.getId());
        worker.setStatus(Agent.Status.IDLE);
        worker.setHostname("laptop");
        worker = agentRepository.saveAndFlush(worker);
        assertThat(worker.getAgentHostInstanceId()).isEqualTo(instance.getId());

        // The retention sweep deletes instances; the work record must survive
        // with a nulled reference (never CASCADE — §3.2a).
        instances.deleteById(instance.getId());
        instances.flush();

        Agent reloaded = agentRepository.findById(worker.getId()).orElseThrow();
        assertThat(reloaded.getAgentHostId()).isEqualTo(host.getId());
        assertThat(reloaded.getAgentHostInstanceId()).isNull();
    }
}
