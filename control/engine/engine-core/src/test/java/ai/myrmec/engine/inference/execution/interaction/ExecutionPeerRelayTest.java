// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.inference.execution.SessionExecution;
import ai.myrmec.engine.inference.execution.SessionExecutionRepository;
import ai.myrmec.engine.node.NodeTransport;
import ai.myrmec.engine.node.StreamRelayRequest;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.user.User;
import ai.myrmec.engine.user.UserPrincipal;
import ai.myrmec.engine.user.UserRepository;
import ai.myrmec.engine.user.UserRole;
import ai.myrmec.engine.user.UserRoleRepository;
import ai.myrmec.engine.websocket.host.payload.ExecutionInteractionCompletePayload;
import ai.myrmec.engine.workflow.ExecutionEventRepository;
import ai.myrmec.engine.workflow.RequestStatus;
import ai.myrmec.engine.workflow.TaskAttemptRepository;
import ai.myrmec.engine.workflow.TaskStatus;
import ai.myrmec.engine.workflow.Workflow;
import ai.myrmec.engine.workflow.WorkflowRepository;
import ai.myrmec.engine.workflow.WorkflowRequest;
import ai.myrmec.engine.workflow.WorkflowRequestRepository;
import ai.myrmec.engine.workflow.WorkflowStatus;
import ai.myrmec.engine.workflow.WorkflowTask;
import ai.myrmec.engine.workflow.WorkflowTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review Fix 3a: the DEAD outbound node relay is now WIRED —
 * {@code ExecutionViewService.fanOutAfterCommit} notifies the LOCAL broker
 * (delivery) AND calls {@code nodeTransport.publishExecutionEvent} (the
 * peer wakeup — the remote arm routes to {@code deliverRemoteFrameRelay}).
 * A recording {@code @Primary} decorator over the REAL transport proves
 * the peer arm fires after commit for VIEW-SERVICE allocations, with the
 * committed row's typed envelope (never empty/inert).
 */
class ExecutionPeerRelayTest extends IntegrationTestBase {

    @Autowired ExecutionViewService viewService;
    @Autowired ExecutionInteractionService interactionService;
    @Autowired ExecutionStreamBroker broker;
    @Autowired RecordingNodeTransport recorded;
    @Autowired TestDataBuilder data;
    @Autowired SessionRepository sessionRepository;
    @Autowired SessionExecutionRepository executions;
    @Autowired ExecutionInteractionRepository interactions;
    @Autowired ExecutionEventRepository executionEventRepository;
    @Autowired TaskAttemptRepository attemptRepository;
    @Autowired WorkflowTaskRepository taskRepository;
    @Autowired WorkflowRequestRepository requestRepository;
    @Autowired WorkflowRepository workflowRepository;
    @Autowired UserRepository userRepository;
    @Autowired UserRoleRepository userRoleRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ai.myrmec.engine.agent.AgentHostInstanceRepository instanceRepository;

    private Project project;
    private Workflow workflow;
    private WorkflowRequest request;
    private WorkflowTask task;
    private ai.myrmec.engine.workflow.TaskAttempt attempt;
    private Session session;
    private SessionExecution execution;
    private User editorUser;

    /**
     * THE recording seam: a @Primary NodeTransport that DELEGATES to the
     * real OSS transport (all arms: conversation fan-out, execution
     * fan-out, handler registration) and records every
     * {@code publishExecutionEvent} call. Consumers keep their normal
     * wiring; the record shows the peer wakeup arm fired.
     */
    @TestConfiguration
    static class RecordingTransportConfig {
        @Bean
        @Primary
        NodeTransport recordingNodeTransport(NodeTransport real) {
            return new RecordingNodeTransport(real);
        }
    }

    static class RecordingNodeTransport implements NodeTransport {
        final ConcurrentLinkedQueue<String> executionEnvelopes =
                new ConcurrentLinkedQueue<>();
        private final NodeTransport delegate;

        RecordingNodeTransport(NodeTransport delegate) {
            this.delegate = delegate;
        }

        @Override
        public void publishToPeers(UUID conversationId, String jsonFrame) {
            delegate.publishToPeers(conversationId, jsonFrame);
        }

        @Override
        public void setFanoutHandler(java.util.function.BiConsumer<UUID, String> handler) {
            delegate.setFanoutHandler(handler);
        }

        @Override
        public void publishExecutionEvent(UUID executionId, String envelopeJson) {
            executionEnvelopes.add(envelopeJson);
            delegate.publishExecutionEvent(executionId, envelopeJson);
        }

        @Override
        public void setExecutionFanoutHandler(
                java.util.function.BiConsumer<StreamRelayRequest, String> handler) {
            delegate.setExecutionFanoutHandler(handler);
        }
    }

    @BeforeEach
    void seed() {
        // The recording bean survives across tests in the class — clear it.
        recorded.executionEnvelopes.clear();
        project = data.project().named("relay-proj").create();
        workflow = workflowOf(project);
        User admin = userRepository.findById(TEST_ADMIN_ID).orElseThrow();

        WorkflowRequest req = new WorkflowRequest();
        req.setWorkflow(workflow);
        req.setWorkflowVersion(1);
        req.setInput(Map.of());
        req.setStatus(RequestStatus.RUNNING);
        req.setBranch("myrmec/relay");
        req.setCreatedBy(admin);
        req.setCreatedAt(Instant.now());
        request = requestRepository.save(req);

        var profile = data.agentProfile().named("relay-profile").create();
        WorkflowTask t = new WorkflowTask();
        t.setRequest(request);
        t.setStepId("step-1");
        t.setAgentProfile(profile);
        t.setInput(Map.of());
        t.setStatus(TaskStatus.RUNNING);
        t.setAttempt(1);
        t.setMaxRetries(1);
        task = taskRepository.save(t);
        attempt = attemptRepository.save(task.createAttempt(null));

        // A live host instance: the §3.4 outbox routing target (NOT NULL
        // host_instance_id on the outbox row; the admission inserts one).
        var instance = instanceRepository.saveAndFlush(
                ai.myrmec.engine.agent.AgentHostInstance.open(
                        data.agent().named("relay-host").create().agent(), null,
                        UUID.randomUUID().toString(), "laptop", 4, Map.of(), "node-1"));

        session = new Session();
        session.setServiceType("WORKFLOW");
        session.setRefId(request.getId());
        session.setProjectId(project.getId());
        session.setKind("ORCHESTRATION_TASK");
        session.setStatus("ACTIVE");
        session.setAllocationState("ACTIVE");
        session.setHostInstanceId(instance.getId());
        session = sessionRepository.save(session);

        execution = new SessionExecution();
        execution.setSessionId(session.getId());
        execution.setServiceType("WORKFLOW");
        execution.setDispatchId(attempt.getId());
        execution.setState(SessionExecution.State.RUNNING);
        execution.setStartedAt(Instant.now());
        execution.setDeadline(Instant.now().plusSeconds(600)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        execution.setInteractionPolicy(InteractionPolicySnapshotService.policyMap(
                InteractionProperties.defaults()));
        execution = executions.saveAndFlush(execution);

        editorUser = seedUser("relay-editor");
        grant(editorUser.getId(), UserRole.Role.EDITOR, project.getId());
    }

    @Test
    @DisplayName("view-service allocation fires the OUTBOUND peer arm after commit "
            + "with the committed row's typed envelope {streamSequence, name, payload, executionId}")
    void allocationFiresOutboundPeerArm() {
        assertThat(recorded.executionEnvelopes).isEmpty();

        viewService.allocateEvent(execution.getId(), "execution.progress",
                Map.of("kind", "progress", "i", "first"));

        assertThat(recorded.executionEnvelopes).hasSize(1);
        ExecutionStreamBroker.ExecutionStreamEventEnvelope envelope =
                ExecutionStreamBroker.ExecutionStreamEventEnvelope
                        .parse(recorded.executionEnvelopes.peek());
        assertThat(envelope).isNotNull();
        assertThat(envelope.executionId()).isEqualTo(execution.getId());
        assertThat(envelope.streamSequence()).isEqualTo(1L);
        assertThat(envelope.name()).isEqualTo("execution.progress");
        assertThat(envelope.ephemeral()).isFalse();
    }

    @Test
    @DisplayName("the interaction OUTCOME arm fires the peer relay too (durable "
            + "settlement = one cursor advance + one peer wakeup)")
    void outcomeSettlementFiresPeerRelay() {
        UUID interactionId = interactionService.admit(
                scope(project.getId()), editor(), UUID.randomUUID(), "relay check")
                .interactionId();
        recorded.executionEnvelopes.clear();

        interactionService.complete(scope(project.getId()), "msg-relay-1",
                completePayload(interactionId, 1L, "settled"));

        assertThat(recorded.executionEnvelopes).isNotEmpty();
        ExecutionStreamBroker.ExecutionStreamEventEnvelope envelope =
                ExecutionStreamBroker.ExecutionStreamEventEnvelope
                        .parse(recorded.executionEnvelopes.peek());
        assertThat(envelope).isNotNull();
        assertThat(envelope.executionId()).isEqualTo(execution.getId());
        assertThat(envelope.name()).isEqualTo("execution.interaction.complete");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private ExecutionInteractionCompletePayload completePayload(UUID interactionId,
                                                                long ordinal,
                                                                String answer) {
        return new ExecutionInteractionCompletePayload(execution.getId(), attempt.getId(),
                interactionId, ordinal,
                new ExecutionInteractionCompletePayload.Answer(answer),
                new ExecutionInteractionCompletePayload.Usage(120, 30, "orch-model"),
                ExecutionInteractionCompletePayload.UsageStatus.KNOWN,
                List.of(), Instant.now());
    }

    private ExecutionScope scope(UUID projectId) {
        return new ExecutionScope(projectId, workflow.getId(), request.getId(),
                task.getId(), attempt.getId(), execution.getId());
    }

    private UserPrincipal editor() {
        return new UserPrincipal(editorUser.getId(), "Test User",
                "user-" + editorUser.getId().toString().substring(0, 8)
                        + "@test.local",
                List.of("proj:" + project.getId() + ":EDITOR"));
    }

    private User seedUser(String suffix) {
        User user = new User();
        user.setEmail("relay-" + suffix + "-" + System.nanoTime() + "@test.local");
        user.setName("Relay " + suffix);
        user.setPasswordHash("$2a$10$dummy");
        user.setProviderCode(ai.myrmec.engine.user.AuthenticationProvider.LOCAL_CODE);
        user.setIsActive(true);
        user.setIsSystem(false);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user);
    }

    private void grant(UUID userId, UserRole.Role role, UUID projectId) {
        UserRole row = new UserRole();
        row.setUserId(userId);
        row.setRole(role);
        row.setScopeType(UserRole.ScopeType.PROJECT);
        row.setProjectId(projectId);
        row.setGrantedByUserId(TEST_ADMIN_ID);
        userRoleRepository.save(row);
    }

    private Workflow workflowOf(Project parent) {
        Workflow wf = new Workflow();
        wf.setProject(parent);
        wf.setName("relay-wf-" + System.nanoTime());
        wf.setSteps(List.of());
        wf.setVersion(1);
        wf.setStatus(WorkflowStatus.PUBLISHED);
        wf.setCreatedBy(userRepository.findById(TEST_ADMIN_ID).orElseThrow());
        return workflowRepository.save(wf);
    }
}
