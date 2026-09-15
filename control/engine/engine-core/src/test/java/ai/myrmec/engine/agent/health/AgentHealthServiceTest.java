// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent.health;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.agent.AgentRepository;
import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.util.ReflectionTestUtils.setField;

/**
 * Phase 9d (unified protocol, P6-T6) — covers the AgentHealthService
 * aggregation logic against a real H2 DB. Online/idle/busy now derive
 * from the host-instance + session allocation state (the legacy
 * AgentConnectionManager socket registry is gone): online = the
 * worker's host has a live OPEN instance; busy = an allocation on the
 * worker's host instance; idle = online with nothing allocated.
 */
class AgentHealthServiceTest extends IntegrationTestBase {

    @Autowired private TestDataBuilder data;
    @Autowired private AgentRepository instanceRepository;
    @Autowired private AgentHostInstanceRepository hostInstanceRepository;
    @Autowired private SessionRepository sessionRepository;

    @Test
    void aggregatesInstanceStateAndQueueDepth() {
        var project = data.project().named("agent-health").create();
        AgentHost agent = data.agent()
                .named("agent-health-agent")
                .inProject(project)
                .create()
                .agent();

        // Fresh heartbeat under the stale threshold.
        Agent fresh = newInstance(agent, Agent.Status.IDLE,
                Instant.now().minus(5, ChronoUnit.SECONDS));
        // Stale heartbeat (> 2 x 30s interval) but still online.
        Agent stale = newInstance(agent, Agent.Status.IDLE,
                Instant.now().minus(10, ChronoUnit.MINUTES));
        // Never registered a heartbeat — offline.
        Agent offline = newInstance(agent, Agent.Status.DEAD,
                Instant.now().minus(2, ChronoUnit.HOURS));

        AgentHealthService svc = new AgentHealthService(
                instanceRepository, hostInstanceRepository, sessionRepository,
                unusedTaskAttemptRepository());
        setField(svc, "heartbeatIntervalSeconds", 30L);

        AgentHealthSnapshot snap = svc.snapshot(agent.getId());

        assertThat(snap.getAgentId()).isEqualTo(agent.getId());
        assertThat(snap.getTotalInstances()).isEqualTo(3);
        // None of the workers has a live OPEN host instance carrying it —
        // the unified wire counts online from host instances, not sockets.
        assertThat(snap.getOnlineInstances()).isZero();
        assertThat(snap.getIdleInstances()).isZero();
        assertThat(snap.getBusyInstances()).isZero();
        assertThat(snap.getStaleInstances()).isZero(); // stale only counts online workers
        assertThat(snap.getInstances()).hasSize(3);
        assertThat(snap.getQueueDepth()).isZero();
        // Latest heartbeat is the freshest of the three. H2 truncates
        // sub-millisecond fractions, so compare with a 1-second window.
        assertThat(snap.getLatestHeartbeatAt())
                .isCloseTo(fresh.getLastHeartbeatAt(),
                        new org.assertj.core.data.TemporalUnitWithinOffset(
                                1, java.time.temporal.ChronoUnit.SECONDS));

        var freshRow = snap.getInstances().stream()
                .filter(i -> i.getInstanceId().equals(fresh.getId()))
                .findFirst().orElseThrow();
        assertThat(freshRow.isIdle()).isFalse(); // offline (no OPEN host instance)
        assertThat(freshRow.getStatus()).isEqualTo("IDLE");
        assertThat(freshRow.getSecondsSinceHeartbeat()).isLessThanOrEqualTo(30L);
    }

    @Test
    void openHostInstanceMarksWorkerOnlineIdleAndAllocationMarksItBusy() {
        var project = data.project().named("agent-health-2").create();
        AgentHost agent = data.agent()
                .named("agent-health-agent-2")
                .inProject(project)
                .create()
                .agent();

        // A live OPEN host instance carrying the worker → online + idle.
        var hostInstance = hostInstanceRepository.save(openInstance(agent));

        Agent worker = newInstance(agent, Agent.Status.IDLE,
                Instant.now().minus(5, ChronoUnit.SECONDS));
        // The worker is "on" the instance when its row points at it.
        worker.setAgentHostInstanceId(hostInstance.getId());
        worker = instanceRepository.save(worker);

        AgentHealthService svc = new AgentHealthService(
                instanceRepository, hostInstanceRepository, sessionRepository,
                unusedTaskAttemptRepository());
        setField(svc, "heartbeatIntervalSeconds", 30L);

        AgentHealthSnapshot idleSnap = svc.snapshot(agent.getId());
        assertThat(idleSnap.getOnlineInstances()).isEqualTo(1);
        assertThat(idleSnap.getIdleInstances()).isEqualTo(1);
        assertThat(idleSnap.getBusyInstances()).isZero();

        // An ACTIVE session allocated on that host instance → busy.
        Session session = new Session();
        session.setServiceType("CONVERSATION");
        session.setRefId(UUID.randomUUID());
        session.setProjectId(project.getId());
        session.setKind("SESSION_KIND_TURN");
        session.setHostInstanceId(hostInstance.getId());
        session.setAllocationState(ai.myrmec.engine.inference.SessionAllocator.ALLOC_STATE_ACTIVE);
        sessionRepository.save(session);

        AgentHealthSnapshot busySnap = svc.snapshot(agent.getId());
        assertThat(busySnap.getOnlineInstances()).isEqualTo(1);
        assertThat(busySnap.getIdleInstances()).isZero();
        assertThat(busySnap.getBusyInstances()).isEqualTo(1);
    }

    private AgentHostInstance openInstance(AgentHost host) {
        // The entity mints rows via the open(...) factory (append-only
        // supervisor-run rows, no setters for lifecycle state).
        return AgentHostInstance.open(host, null, "health-test-host",
                1, java.util.Map.of(), "test-node");
    }

    private Agent newInstance(AgentHost agent, Agent.Status status, Instant heartbeat) {
        Agent inst = new Agent();
        inst.setAgentHostId(agent.getId());
        inst.setHostname("host-" + heartbeat);
        inst.setRuntimeVersion("0.0.0");
        inst.setStatus(status);
        inst.setRegisteredAt(heartbeat.minus(1, ChronoUnit.HOURS));
        inst.setLastHeartbeatAt(heartbeat);
        return instanceRepository.save(inst);
    }

    private ai.myrmec.engine.workflow.TaskAttemptRepository unusedTaskAttemptRepository() {
        ai.myrmec.engine.workflow.TaskAttemptRepository r =
                org.mockito.Mockito.mock(ai.myrmec.engine.workflow.TaskAttemptRepository.class);
        org.mockito.Mockito.lenient().when(r.countByAgentInstanceIdAndStatus(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(0L);
        return r;
    }
}