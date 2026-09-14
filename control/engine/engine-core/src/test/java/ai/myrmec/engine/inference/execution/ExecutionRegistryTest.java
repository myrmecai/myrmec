// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationMessage;
import ai.myrmec.engine.conversation.ConversationService;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §11.2/§11.3 execution FSM transitions and invariants under the same
 * pessimistic-lock discipline as {@link ai.myrmec.engine.inference.SessionAllocatorTest}.
 */
class ExecutionRegistryTest extends IntegrationTestBase {

    @Autowired ExecutionRegistry registry;
    @Autowired ai.myrmec.engine.inference.SessionAllocator allocator;
    @Autowired SessionExecutionRepository executionRepository;
    @Autowired AgentHostInstanceRepository instances;
    @Autowired TestDataBuilder data;
    @Autowired ConversationService conversationService;

    private AgentHostInstance openInstance(int pool) {
        AgentProfile profile = data.agentProfile().named("x-profile").create();
        AgentHostCreationResult created =
                data.agent().named("x-host").withProfile(profile).withMaxAgents(10).create();
        return instances.saveAndFlush(AgentHostInstance.open(
                created.agent(), null, UUID.randomUUID().toString(), "laptop", pool, Map.of(), "node-1"));
    }

    private Project project() {
        return data.project().named("x-proj").create();
    }

    private UUID activeConversationSession() {
        AgentHostInstance instance = openInstance(2);
        Project project = project();

        UUID sessionId = allocator.offer("CONVERSATION", UUID.randomUUID(), "CONVERSATION",
                project.getId(), instance.getAgentHostId()).orElseThrow();
        assertThat(allocator.accept(sessionId)).isTrue();
        assertThat(allocator.confirmOpened(sessionId, "slot")).isTrue();
        return sessionId;
    }

    private UUID activeOrchestrationSession() {
        AgentHostInstance instance = openInstance(2);
        Project project = project();

        UUID refId = UUID.randomUUID();
        UUID sessionId = allocator.offer("ORCHESTRATION_TASK", refId, "WORKFLOW",
                project.getId(), instance.getAgentHostId()).orElseThrow();
        assertThat(allocator.accept(sessionId)).isTrue();
        assertThat(allocator.confirmOpened(sessionId, "slot")).isTrue();
        return sessionId;
    }

    @Test
    void conversationSessionAdmitsOneInFlightExecutionAndAssignsSequenceNumbers() {
        UUID sessionId = activeConversationSession();

        Optional<SessionExecution> first = registry.start(sessionId, "r1", Instant.now().plusSeconds(60), Map.of());
        assertThat(first).isPresent();
        SessionExecution e1 = first.get();
        assertThat(e1.getState()).isEqualTo(SessionExecution.State.STARTING);
        assertThat(e1.getSequenceNo()).isEqualTo(1);

        // Second start while STARTING is refused (§11.3.4).
        Optional<SessionExecution> second = registry.start(sessionId, "r2", Instant.now().plusSeconds(60), Map.of());
        assertThat(second).isEmpty();

        assertThat(registry.accept(e1.getId(), Instant.now(), "m1", UUID.randomUUID(), "d1")).isTrue();
        SessionExecution running = executionRepository.findById(e1.getId()).orElseThrow();
        assertThat(running.getState()).isEqualTo(SessionExecution.State.RUNNING);

        // Start after accept still refused.
        Optional<SessionExecution> third = registry.start(sessionId, "r3", Instant.now().plusSeconds(60), Map.of());
        assertThat(third).isEmpty();

        // Terminal and new turn: sequenceNo advances.
        assertThat(registry.terminal(e1.getId(), SessionExecution.State.COMPLETED,
                "m-terminal", Map.of("k", "v"))).isTrue();
        Optional<SessionExecution> fourth = registry.start(sessionId, "r4", Instant.now().plusSeconds(60), Map.of());
        assertThat(fourth).isPresent();
        assertThat(fourth.get().getSequenceNo()).isEqualTo(2);
    }

    @Test
    void orchestrationSessionAcceptsExactlyOneExecutionEver() {
        UUID sessionId = activeOrchestrationSession();

        Optional<SessionExecution> first = registry.start(sessionId, null, Instant.now().plusSeconds(60), Map.of());
        assertThat(first).isPresent();
        SessionExecution e1 = first.get();
        assertThat(e1.getSequenceNo()).isNull();

        // A second start is refused even though the first is still STARTING.
        Optional<SessionExecution> second = registry.start(sessionId, null, Instant.now().plusSeconds(60), Map.of());
        assertThat(second).isEmpty();

        // And after terminal it is still refused (exactly one ever).
        assertThat(registry.accept(e1.getId(), Instant.now(), "m1", UUID.randomUUID(), "d1")).isTrue();
        assertThat(registry.terminal(e1.getId(), SessionExecution.State.COMPLETED,
                "m-terminal", Map.of())).isTrue();
        Optional<SessionExecution> third = registry.start(sessionId, null, Instant.now().plusSeconds(60), Map.of());
        assertThat(third).isEmpty();
    }

    @Test
    void terminalIsExactlyOnceAndDeduplicatesByMessageId() {
        UUID sessionId = activeConversationSession();
        SessionExecution execution = registry.start(sessionId, "r1", Instant.now().plusSeconds(60), Map.of())
                .orElseThrow();
        assertThat(registry.accept(execution.getId(), Instant.now(), "m1", UUID.randomUUID(), "d1")).isTrue();

        Map<String, Object> payload = Map.of("outcome", "ok");
        assertThat(registry.terminal(execution.getId(), SessionExecution.State.COMPLETED,
                "m1", payload)).isTrue();
        SessionExecution completed = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(completed.getState()).isEqualTo(SessionExecution.State.COMPLETED);
        assertThat(completed.getTerminalMessageId()).isEqualTo("m1");
        assertThat(completed.getTerminalPayload()).isEqualTo(payload);

        // Idempotent replay with same messageId.
        assertThat(registry.terminal(execution.getId(), SessionExecution.State.COMPLETED,
                "m1", Map.of("outcome", "other"))).isTrue();
        SessionExecution still = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(still.getState()).isEqualTo(SessionExecution.State.COMPLETED);
        assertThat(still.getTerminalMessageId()).isEqualTo("m1");
        assertThat(still.getTerminalPayload()).isEqualTo(payload);

        // Conflicting messageId fails closed; row unchanged.
        assertThat(registry.terminal(execution.getId(), SessionExecution.State.FAILED,
                "m2", Map.of("outcome", "bad"))).isFalse();
        SessionExecution unchanged = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(unchanged.getState()).isEqualTo(SessionExecution.State.COMPLETED);
        assertThat(unchanged.getTerminalMessageId()).isEqualTo("m1");
        assertThat(unchanged.getTerminalPayload()).isEqualTo(payload);
    }

    @Test
    void acceptAndRejectAreSingleOutcome() {
        UUID sessionId = activeConversationSession();
        SessionExecution execution = registry.start(sessionId, "r1", Instant.now().plusSeconds(60), Map.of())
                .orElseThrow();

        assertThat(registry.accept(execution.getId(), Instant.now(), "m1", UUID.randomUUID(), "d1")).isTrue();
        assertThat(registry.accept(execution.getId(), Instant.now(), "m1", UUID.randomUUID(), "d1")).isFalse();

        SessionExecution running = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(running.getState()).isEqualTo(SessionExecution.State.RUNNING);

        // Reject on RUNNING is refused.
        assertThat(registry.reject(execution.getId())).isFalse();
        assertThat(executionRepository.findById(execution.getId()).orElseThrow().getState())
                .isEqualTo(SessionExecution.State.RUNNING);
    }

    @Test
    void cancelIsAMarkNotATerminal() {
        UUID sessionId = activeConversationSession();
        SessionExecution execution = registry.start(sessionId, "r1", Instant.now().plusSeconds(60), Map.of())
                .orElseThrow();
        assertThat(registry.accept(execution.getId(), Instant.now(), "m1", UUID.randomUUID(), "d1")).isTrue();

        assertThat(registry.requestCancel(execution.getId())).isTrue();
        SessionExecution cancelling = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(cancelling.getState()).isEqualTo(SessionExecution.State.CANCELLING);
        assertThat(cancelling.getTerminalMessageId()).isNull();

        // The real terminal frame still records.
        assertThat(registry.terminal(execution.getId(), SessionExecution.State.CANCELLED,
                "m1", Map.of("reason", "user"))).isTrue();
        SessionExecution cancelled = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(cancelled.getState()).isEqualTo(SessionExecution.State.CANCELLED);
        assertThat(cancelled.getTerminalMessageId()).isEqualTo("m1");

        // requestCancel after terminal is idempotent true.
        assertThat(registry.requestCancel(execution.getId())).isTrue();
    }

    @Test
    void acknowledgeEventSequenceAdvancesCursorIdempotently() {
        UUID sessionId = activeConversationSession();
        assertThat(registry.acknowledgeEventSequence(sessionId, 5L)).isEqualTo(5L);
        assertThat(registry.acknowledgeEventSequence(sessionId, 3L)).isEqualTo(5L);
        assertThat(registry.acknowledgeEventSequence(sessionId, 7L)).isEqualTo(7L);
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getHighestContiguousSequence())
                .isEqualTo(7L);
    }

    @Test
    void startRequiresAllocationActive() {
        AgentHostInstance instance = openInstance(2);
        Project project = project();
        // OFFERED session (not ACTIVE).
        UUID sessionId = allocator.offer("CONVERSATION", UUID.randomUUID(), "CONVERSATION",
                project.getId(), instance.getAgentHostId()).orElseThrow();

        assertThat(registry.start(sessionId, "r1", Instant.now().plusSeconds(60), Map.of())).isEmpty();
    }
}
