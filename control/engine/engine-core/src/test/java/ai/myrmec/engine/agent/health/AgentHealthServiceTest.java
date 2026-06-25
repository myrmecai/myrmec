package ai.myrmec.engine.agent.health;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentRepository;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.websocket.AgentConnectionManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

/**
 * Phase 9d — covers the AgentHealthService aggregation logic against a
 * real H2 DB plus a stubbed {@link AgentConnectionManager} (the manager
 * holds in-process WS state which we can't materialise in an
 * integration test). Confirms heartbeat-staleness, idle-vs-busy
 * counting, and per-instance breakdown shape.
 */
class AgentHealthServiceTest extends IntegrationTestBase {

    @Autowired private TestDataBuilder data;
    @Autowired private AgentRepository instanceRepository;

    @Test
    void aggregatesInstanceStateAndQueueDepth() {
        var project = data.project().named("agent-health").create();
        AgentProfile profile = data.agentProfile()
                .named("agent-health-profile")
                .withSystemPrompt("test")
                .create();
        AgentHost agent = data.agent()
                .named("agent-health-agent")
                .withProfile(profile)
                .inProject(project)
                .create()
                .agent();

        // Two IDLE instances, one fresh + one stale; one DEAD.
        Agent fresh = newInstance(agent, Agent.Status.IDLE,
                Instant.now().minus(5, ChronoUnit.SECONDS));
        Agent stale = newInstance(agent, Agent.Status.IDLE,
                Instant.now().minus(10, ChronoUnit.MINUTES));
        Agent offline = newInstance(agent, Agent.Status.DEAD,
                Instant.now().minus(2, ChronoUnit.HOURS));

        AgentConnectionManager mockManager = mock(AgentConnectionManager.class);
        lenient().when(mockManager.isAgentIdle(fresh.getId())).thenReturn(true);
        lenient().when(mockManager.isAgentIdle(stale.getId())).thenReturn(false);
        lenient().when(mockManager.isAgentIdle(offline.getId())).thenReturn(false);

        AgentHealthService svc = new AgentHealthService(
                instanceRepository, mockManager,
                /* taskAttemptRepo */ unusedTaskAttemptRepository());
        setField(svc, "heartbeatIntervalSeconds", 30L);

        AgentHealthSnapshot snap = svc.snapshot(agent.getId());

        assertThat(snap.getAgentId()).isEqualTo(agent.getId());
        assertThat(snap.getTotalInstances()).isEqualTo(3);
        assertThat(snap.getOnlineInstances()).isEqualTo(2);
        assertThat(snap.getIdleInstances()).isEqualTo(1);  // fresh
        assertThat(snap.getBusyInstances()).isEqualTo(1);  // stale (online but not idle)
        assertThat(snap.getStaleInstances()).isEqualTo(1); // stale row > 60s old
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
        assertThat(freshRow.isStale()).isFalse();
        assertThat(freshRow.isIdle()).isTrue();
        assertThat(freshRow.getStatus()).isEqualTo("IDLE");
        assertThat(freshRow.getSecondsSinceHeartbeat()).isLessThanOrEqualTo(30L);
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
                mock(ai.myrmec.engine.workflow.TaskAttemptRepository.class);
        lenient().when(r.countByAgentInstanceIdAndStatus(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(0L);
        return r;
    }
}
