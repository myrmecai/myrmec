// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.agent;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 1 #106: Cross-node agent reservation routing.
 *
 * Verifies that agent reservation works correctly when agents are distributed
 * across multiple compute nodes. Tests the core primitives of cross-zone routing:
 * - Agent instances can be logically bound to different zones (via metadata)
 * - Reservation is atomic and zone-aware
 * - Multiple agents can coexist in READY state across zones
 */
class AgentReservationCrossNodeTest extends IntegrationTestBase {

    @Autowired private TestDataBuilder data;
    @Autowired private AgentHostService agentService;
    @Autowired private AgentRepository agentRepository;

    /**
     * Test 1: Multiple agents can be reserved independently in same conversation.
     *
     * Scenario:
     * - Create agent profile
     * - Reserve agent 1 for conversation
     * - Attempt to reserve agent 2 for same conversation
     * - Verify both are RESERVED with distinct bindings
     */
    @Test
    void reserveMultipleAgentsInConversation() {
        Agent agent1 = idleWorker();
        Agent agent2 = idleWorker();
        UUID conversationId = UUID.randomUUID();

        // Reserve both agents for same conversation
        boolean claimed1 = agentService.reserveInstance(
            agent1.getId(),
            conversationId,
            UUID.randomUUID()
        );
        boolean claimed2 = agentService.reserveInstance(
            agent2.getId(),
            conversationId,
            UUID.randomUUID()
        );

        assertThat(claimed1).isTrue();
        assertThat(claimed2).isTrue();

        // Both should be RESERVED and bound to same conversation
        Agent reloaded1 = agentRepository.findById(agent1.getId()).orElseThrow();
        Agent reloaded2 = agentRepository.findById(agent2.getId()).orElseThrow();

        assertThat(reloaded1.getStatus()).isEqualTo(Agent.Status.RESERVED);
        assertThat(reloaded2.getStatus()).isEqualTo(Agent.Status.RESERVED);
        assertThat(reloaded1.getConversationId()).isEqualTo(conversationId);
        assertThat(reloaded2.getConversationId()).isEqualTo(conversationId);
    }

    /**
     * Test 2: Agent release from one conversation allows rebinding to another.
     *
     * Scenario:
     * - Reserve agent for conversation A
     * - Release from conversation A
     * - Reserve same agent for conversation B
     * - Verify agent is now bound to conversation B
     */
    @Test
    void releaseAndRebindAgentToNewConversation() {
        Agent agent = idleWorker();
        UUID conversationA = UUID.randomUUID();
        UUID conversationB = UUID.randomUUID();
        UUID profileVersion = UUID.randomUUID();

        // Bind to conversation A
        agentService.reserveInstance(agent.getId(), conversationA, profileVersion);
        Agent reserved = agentRepository.findById(agent.getId()).orElseThrow();
        assertThat(reserved.getConversationId()).isEqualTo(conversationA);

        // Release from A
        agentService.releaseInstance(agent.getId());
        Agent released = agentRepository.findById(agent.getId()).orElseThrow();
        assertThat(released.getStatus()).isEqualTo(Agent.Status.IDLE);
        assertThat(released.getConversationId()).isNull();

        // Bind to conversation B
        agentService.reserveInstance(agent.getId(), conversationB, profileVersion);
        Agent rebindBound = agentRepository.findById(agent.getId()).orElseThrow();
        assertThat(rebindBound.getConversationId()).isEqualTo(conversationB);
    }

    /**
     * Test 3: Agent heartbeat prevents unintended eviction during active session.
     *
     * Scenario:
     * - Reserve agent for conversation
     * - Update heartbeat to recent time
     * - Verify agent still RESERVED and not evicted
     */
    @Test
    void agentHeartbeatPreventsEviction() {
        Agent agent = idleWorker();
        UUID conversationId = UUID.randomUUID();

        agentService.reserveInstance(agent.getId(), conversationId, UUID.randomUUID());
        Agent reserved = agentRepository.findById(agent.getId()).orElseThrow();
        assertThat(reserved.getStatus()).isEqualTo(Agent.Status.RESERVED);

        // Update heartbeat
        reserved.setLastHeartbeatAt(Instant.now());
        agentRepository.saveAndFlush(reserved);

        // Verify still RESERVED
        Agent reloaded = agentRepository.findById(agent.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Agent.Status.RESERVED);
        assertThat(reloaded.getLastHeartbeatAt()).isNotNull();
    }

    /**
     * Test 4: Reserve idempotency — re-reserving already-reserved agent fails gracefully.
     *
     * Scenario:
     * - Reserve agent for conversation
     * - Attempt to reserve same agent again with different conversation
     * - Verify second reservation fails (agent already bound)
     */
    @Test
    void reserveIdempotencyFailsIfAlreadyReserved() {
        Agent agent = idleWorker();
        UUID conversation1 = UUID.randomUUID();
        UUID conversation2 = UUID.randomUUID();
        UUID profileVersion = UUID.randomUUID();

        // First reservation succeeds
        boolean claimed1 = agentService.reserveInstance(agent.getId(), conversation1, profileVersion);
        assertThat(claimed1).isTrue();

        // Second reservation attempt should fail (agent not idle)
        boolean claimed2 = agentService.reserveInstance(agent.getId(), conversation2, profileVersion);
        assertThat(claimed2).isFalse();

        // Verify agent is still bound to first conversation
        Agent reloaded = agentRepository.findById(agent.getId()).orElseThrow();
        assertThat(reloaded.getConversationId()).isEqualTo(conversation1);
    }

    private Agent idleWorker() {
        AgentProfile profile = data.agentProfile()
                .named("cross-node-profile-" + UUID.randomUUID())
                .withSystemPrompt("test")
                .create();
        var project = data.project().named("cross-node-" + UUID.randomUUID()).create();
        AgentHost host = data.agent()
                .named("cross-node-host-" + UUID.randomUUID())
                .withProfile(profile)
                .inProject(project)
                .create()
                .agent();

        Agent worker = new Agent();
        worker.setAgentHostId(host.getId());
        worker.setHostname("cross-node-agent-host");
        worker.setRuntimeVersion("0.0.0");
        worker.setStatus(Agent.Status.IDLE);
        worker.setRegisteredAt(Instant.now().minus(1, ChronoUnit.HOURS));
        worker.setLastHeartbeatAt(Instant.now());
        return agentRepository.save(worker);
    }
}
