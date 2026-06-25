// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationEvent;
import ai.myrmec.engine.conversation.ConversationEventReason;
import ai.myrmec.engine.conversation.ConversationEventRepository;
import ai.myrmec.engine.conversation.ConversationRepository;
import ai.myrmec.engine.node.EngineNode;
import ai.myrmec.engine.node.EngineNodeRepository;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice #106a — covers the conversation-socket home-node failover sweep and
 * the idempotent re-attach path against a real H2 DB (agent-concurrency
 * §9.11). The sweep timer is disabled under the e2e profile; these tests
 * drive the package-private {@code reHomeLostWorkers} method directly.
 */
class HomeNodeFailoverServiceTest extends IntegrationTestBase {

    @Autowired private TestDataBuilder data;
    @Autowired private HomeNodeFailoverService failover;
    @Autowired private AgentService agentService;
    @Autowired private AgentRepository instanceRepository;
    @Autowired private EngineNodeRepository nodeRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private ConversationEventRepository eventRepository;

    @BeforeEach
    void clearNodes() {
        // The e2e profile leaves engine_nodes empty (self-registration off), so
        // these tests own the table; scrub it so findDownNodeIds() is scoped to
        // exactly the rows a test creates.
        nodeRepository.deleteAll();
    }

    // ---- failover sweep unit cases (§9.11 test gate) ----------------------

    @Test
    void downNodeWithLiveHostIsReHomedAndLogsHomeNodeLost() {
        String downNode = downNode();
        UUID conversationId = conversation();
        Agent worker = boundWorker(downNode, Instant.now(), conversationId); // heartbeat fresh = host live

        failover.reHomeLostWorkers(Instant.now());

        Agent reloaded = instanceRepository.findById(worker.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Agent.Status.CONNECTING);
        assertThat(reloaded.getHomeNodeId()).isNull();

        List<ConversationEvent> log = eventRepository.findByConversationIdOrderBySeqAsc(conversationId);
        assertThat(log).extracting(ConversationEvent::getReasonCode)
                .containsExactly(ConversationEventReason.HOME_NODE_LOST);
        assertThat(log.get(0).getFromState()).isEqualTo("BOUND");
        assertThat(log.get(0).getToState()).isEqualTo("CONNECTING");
    }

    @Test
    void downNodeWithDeadHostIsLeftForTheHostLostReaper() {
        String downNode = downNode();
        UUID conversationId = conversation();
        // Heartbeat stale (older than the 70s host-lost threshold) = host gone.
        Agent worker = boundWorker(downNode,
                Instant.now().minus(5, ChronoUnit.MINUTES), conversationId);

        failover.reHomeLostWorkers(Instant.now());

        Agent reloaded = instanceRepository.findById(worker.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Agent.Status.BOUND);
        assertThat(reloaded.getHomeNodeId()).isEqualTo(downNode);
        assertThat(eventRepository.findByConversationIdOrderBySeqAsc(conversationId)).isEmpty();
    }

    @Test
    void upNodeIsANoOp() {
        String upNode = upNode();
        UUID conversationId = conversation();
        Agent worker = boundWorker(upNode, Instant.now(), conversationId);

        failover.reHomeLostWorkers(Instant.now());

        Agent reloaded = instanceRepository.findById(worker.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Agent.Status.BOUND);
        assertThat(reloaded.getHomeNodeId()).isEqualTo(upNode);
        assertThat(eventRepository.findByConversationIdOrderBySeqAsc(conversationId)).isEmpty();
    }

    // ---- re-home end-to-end: BOUND -> CONNECTING -> BOUND -----------------

    @Test
    void reHomeEndToEndReBindsToTheNewHomeNode() {
        String downNode = downNode();
        UUID conversationId = conversation();
        Agent worker = boundWorker(downNode, Instant.now(), conversationId);

        // Sweep re-homes the worker off the dead node (BOUND -> CONNECTING).
        failover.reHomeLostWorkers(Instant.now());
        assertThat(instanceRepository.findById(worker.getId()).orElseThrow().getStatus())
                .isEqualTo(Agent.Status.CONNECTING);

        // The Agent re-attaches its conversation socket on a live replica
        // (a self-registered UP node, so agents.home_node_id stays referential).
        String newHome = upNode();
        boolean bound = agentService.attachConversation(worker.getId(), conversationId, newHome);

        assertThat(bound).isTrue();
        Agent reloaded = instanceRepository.findById(worker.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Agent.Status.BOUND);
        // INSTANCE_BOUND re-emitted carrying the NEW home node.
        assertThat(reloaded.getHomeNodeId()).isEqualTo(newHome);
        Conversation conv = conversationRepository.findById(conversationId).orElseThrow();
        assertThat(conv.getHomeNodeId()).isEqualTo(newHome);

        List<ConversationEvent> log = eventRepository.findByConversationIdOrderBySeqAsc(conversationId);
        assertThat(log).extracting(ConversationEvent::getReasonCode).containsExactly(
                ConversationEventReason.HOME_NODE_LOST,
                ConversationEventReason.INSTANCE_BOUND);
        // The re-emitted INSTANCE_BOUND lands the worker back in BOUND.
        assertThat(log.get(1).getFromState()).isEqualTo("CONNECTING");
        assertThat(log.get(1).getToState()).isEqualTo("BOUND");
    }

    // ---- idempotent re-attach on the same tuple --------------------------

    @Test
    void duplicateAttachOnSameTupleStaysBoundWithoutDuplicateEvents() {
        UUID conversationId = conversation();
        Agent worker = idleWorker();
        // Live home node backing agents.home_node_id (FK to engine_nodes).
        String home = upNode();

        agentService.reserveInstance(worker.getId(), conversationId, UUID.randomUUID());
        agentService.confirmBind(worker.getId(), conversationId);
        assertThat(agentService.attachConversation(worker.getId(), conversationId, home)).isTrue();

        // Agent Supervisor reconnected after a transient blip and re-sent
        // conversation.attach for the SAME (conversationId, homeNodeId, agent).
        assertThat(agentService.attachConversation(worker.getId(), conversationId, home)).isTrue();

        Agent reloaded = instanceRepository.findById(worker.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Agent.Status.BOUND);
        assertThat(reloaded.getHomeNodeId()).isEqualTo(home);

        List<ConversationEvent> log = eventRepository.findByConversationIdOrderBySeqAsc(conversationId);
        // Exactly ONE INSTANCE_BOUND (single BOUND, no duplicate bind) and the
        // re-attach recorded as a CONVERSATION_REATTACHED liveness event.
        assertThat(log).extracting(ConversationEvent::getReasonCode).containsExactly(
                ConversationEventReason.RESERVED,
                ConversationEventReason.BIND_ACKED,
                ConversationEventReason.INSTANCE_BOUND,
                ConversationEventReason.CONVERSATION_REATTACHED);
        assertThat(log).filteredOn(e -> e.getReasonCode() == ConversationEventReason.INSTANCE_BOUND)
                .hasSize(1);
        assertThat(log.get(3).getFromState()).isEqualTo("BOUND");
        assertThat(log.get(3).getToState()).isEqualTo("BOUND");
    }

    // ---- helpers ----------------------------------------------------------

    private String downNode() {
        return saveNode("down-node-" + UUID.randomUUID(), EngineNode.Status.DOWN);
    }

    private String upNode() {
        return saveNode("up-node-" + UUID.randomUUID(), EngineNode.Status.UP);
    }

    private String saveNode(String nodeId, EngineNode.Status status) {
        EngineNode node = new EngineNode();
        node.setNodeId(nodeId);
        node.setAddress(nodeId + ":9090");
        node.setStatus(status);
        node.setStartedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        node.setLastHeartbeatAt(Instant.now());
        nodeRepository.save(node);
        return nodeId;
    }

    private UUID conversation() {
        Project project = data.project().named("failover-" + UUID.randomUUID()).create();
        Conversation conversation = new Conversation();
        conversation.setProjectId(project.getId());
        conversation.setTitle("failover-test");
        return conversationRepository.save(conversation).getId();
    }

    private Agent boundWorker(String homeNodeId, Instant lastHeartbeatAt, UUID conversationId) {
        Agent worker = idleWorker();
        worker.setStatus(Agent.Status.BOUND);
        worker.setHomeNodeId(homeNodeId);
        worker.setConversationId(conversationId);
        worker.setProfileVersionId(UUID.randomUUID());
        worker.setLastHeartbeatAt(lastHeartbeatAt);
        worker.setStateChangedAt(Instant.now());
        return instanceRepository.save(worker);
    }

    private Agent idleWorker() {
        AgentProfile profile = data.agentProfile()
                .named("failover-profile-" + UUID.randomUUID())
                .withSystemPrompt("test")
                .create();
        var project = data.project().named("failover-host-" + UUID.randomUUID()).create();
        AgentHost host = data.agent()
                .named("failover-host-" + UUID.randomUUID())
                .withProfile(profile)
                .inProject(project)
                .create()
                .agent();

        Agent worker = new Agent();
        worker.setAgentHostId(host.getId());
        worker.setHostname("failover-host");
        worker.setRuntimeVersion("0.0.0");
        worker.setStatus(Agent.Status.IDLE);
        worker.setRegisteredAt(Instant.now().minus(1, ChronoUnit.HOURS));
        worker.setLastHeartbeatAt(Instant.now());
        return instanceRepository.save(worker);
    }
}