// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine._system.exception.DuplicateResourceException;
import ai.myrmec.engine._system.exception.ResourceInUseException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.inference.execution.SessionExecution;
import ai.myrmec.engine.inference.execution.SessionExecutionRepository;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.user.User;
import ai.myrmec.engine.user.UserPrincipal;
import ai.myrmec.engine.user.UserRepository;
import ai.myrmec.engine.user.UserRole;
import ai.myrmec.engine.user.UserRoleRepository;
import ai.myrmec.engine.websocket.host.payload.ExecutionControlRequestPayload;
import ai.myrmec.engine.workflow.RequestStatus;
import ai.myrmec.engine.workflow.TaskAttempt;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 6 (plan 2026-10-03-session-interaction, Task 6): the control service's
 * authorization matrix, the transactional intent algorithm (authorize → lock
 * → replay → reject terminal → allocate revision → insert intent + exact
 * envelope → commit → dispatch stored envelope → 202), and the chat-CANCEL
 * confirmation flow. Wire authority: protocol §22.4/§22.7; API authority:
 * plan section 4.
 */
class ExecutionControlServiceTest extends IntegrationTestBase {

    @Autowired ExecutionControlService service;
    @Autowired ExecutionCommandOutboxDispatcher dispatcher;
    @Autowired ControlConfirmationSweeper sweeper;
    @Autowired TestDataBuilder data;
    @Autowired SessionRepository sessionRepository;
    @Autowired SessionExecutionRepository executions;
    @Autowired ExecutionInteractionRepository interactions;
    @Autowired ExecutionControlRequestRepository controlRequests;
    @Autowired ExecutionCommandOutboxRepository outboxRepository;
    @Autowired WorkflowRequestRepository requestRepository;
    @Autowired ai.myrmec.engine.project.ProjectRepository projectRepository;
    @Autowired WorkflowRepository workflowRepository;
    @Autowired WorkflowTaskRepository taskRepository;
    @Autowired TaskAttemptRepository attemptRepository;
    @Autowired AgentHostInstanceRepository instances;
    @Autowired UserRepository userRepository;
    @Autowired UserRoleRepository userRoleRepository;
    @Autowired PlatformTransactionManager transactionManager;

    private Project project;
    private Workflow workflow;
    private WorkflowRequest request;
    private WorkflowTask task;
    private TaskAttempt attempt;
    private Session session;
    private SessionExecution execution;
    private ExecutionInteraction interaction;
    private User editorUser;
    private User viewerUser;

    @BeforeEach
    void seed() {
        project = data.project().named("ctrl-proj").create();
        workflow = workflowOf(project);
        User admin = userRepository.findById(TEST_ADMIN_ID).orElseThrow();

        WorkflowRequest req = new WorkflowRequest();
        req.setWorkflow(workflow);
        req.setWorkflowVersion(1);
        req.setInput(Map.of());
        req.setStatus(RequestStatus.RUNNING);
        req.setBranch("myrmec/ctrl");
        req.setCreatedBy(admin);
        req.setCreatedAt(Instant.now());
        request = requestRepository.save(req);

        var profile = data.agentProfile().named("ctrl-profile").create();
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

        // A live host instance so the session carries a routing target; the
        // dispatcher's actual send is bean-injected (the test observes rows).
        AgentHostInstance instance = instances.saveAndFlush(AgentHostInstance.open(
                data.agent().named("ctrl-host").create().agent(), null,
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

        editorUser = seedUser("editor");
        viewerUser = seedUser("viewer");
        grant(editorUser.getId(), UserRole.Role.EDITOR, project.getId());
        grant(viewerUser.getId(), UserRole.Role.VIEWER, project.getId());
    }

    // ------------------------------------------------------------------
    // Authorization matrix (§4: EDITOR-equivalent controls; VIEWER reads)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("HOLD: editor allowed; intent + exact outbox row stored; revision 1")
    void editorHoldCreatesIntentAndStoresEnvelope() {
        ControlReceipt receipt = control(editor(), UUID.randomUUID(), "HOLD");

        assertThat(receipt.controlRequestId()).isNotNull();
        assertThat(receipt.controlRevision()).isEqualTo(1L);
        assertThat(receipt.status()).isEqualTo("ACCEPTED");

        List<ExecutionControlRequest> rows = rowsByCreated(execution.getId());
        assertThat(rows).hasSize(1);
        ExecutionControlRequest row = rows.get(0);
        assertThat(row.getAction()).isEqualTo("HOLD");
        assertThat(row.getOrigin()).isEqualTo("BUTTON");
        assertThat(row.getStatus()).isEqualTo(InteractionControlStatus.ACCEPTED);
        assertThat(row.getControlRevision()).isEqualTo(1L);
        assertThat(row.getCommandMessageId()).isNotNull();
        assertThat(row.getActorUserId()).isEqualTo(editorUser.getId());

        // §22.4: NO optimistic HELD — the engine records intent only.
        assertThat(currentExecution().getHoldState()).isEqualTo("RUNNING");

        // §3.4: the exact stored envelope; the row id IS the wire messageId.
        ExecutionCommandOutbox entry = outboxRepository
                .findByExecutionIdOrderBySequenceAsc(execution.getId()).get(0);
        assertThat(entry.getId().toString()).isEqualTo(row.getCommandMessageId());
        assertThat(entry.getType()).isEqualTo("execution.control");
        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(entry.getEnvelope().get("type")).isEqualTo("execution.control");
        assertThat(String.valueOf(entry.getEnvelope().get("messageId")))
                .isEqualTo(entry.getId().toString());
        assertThat(payloadOf(entry).get("action")).isEqualTo("HOLD");
        assertThat(((Number) payloadOf(entry).get("controlRevision")).longValue())
                .isEqualTo(1L);
        assertThat(payloadOf(entry).get("executionId"))
                .isEqualTo(execution.getId().toString());
        assertThat(payloadOf(entry).get("dispatchId")).isEqualTo(attempt.getId().toString());
        assertThat(entry.getPayloadDigest()).hasSize(64);
        assertThat(entry.getHostInstanceId()).isEqualTo(session.getHostInstanceId());
        assertThat(entry.getSessionId()).isEqualTo(session.getId());
    }

    @Test
    @DisplayName("identical HOLD retry replays the SAME record (digest idempotency)")
    void identicalHoldRetryReplaysSameRecord() {
        UUID clientRequestId = UUID.randomUUID();
        ControlReceipt first = control(editor(), clientRequestId, "HOLD");
        ControlReceipt replay = control(editor(), clientRequestId, "HOLD");

        assertThat(replay.controlRequestId()).isEqualTo(first.controlRequestId());
        assertThat(replay.controlRevision()).isEqualTo(first.controlRevision());
        assertThat(rowsByCreated(execution.getId())).hasSize(1);
        assertThat(outboxRepository.findByExecutionIdOrderBySequenceAsc(execution.getId()))
                .hasSize(1);
    }

    @Test
    @DisplayName("identical HOLD retry AFTER the execution went terminal returns the SAME receipt, not 409")
    void identicalRetryAfterTerminalReplaysSameRecord() {
        UUID clientRequestId = UUID.randomUUID();
        ControlReceipt first = control(editor(), clientRequestId, "HOLD");

        // Stamp terminal on the LIVE row (a stale seed entity would regress
        // the §3.5 cursor — Fix 1).
        tx(() -> {
            SessionExecution locked = executions.findWithLockById(execution.getId())
                    .orElseThrow();
            locked.setState(SessionExecution.State.CANCELLED);
            locked.setTerminalMessageId("tm-late");
            executions.save(locked);
        });

        ControlReceipt replay = control(editor(), clientRequestId, "HOLD");
        assertThat(replay.controlRequestId()).isEqualTo(first.controlRequestId());
        assertThat(replay.status()).isEqualTo(first.status());
        assertThat(replay.controlRevision()).isEqualTo(first.controlRevision());
        assertThat(rowsByCreated(execution.getId())).hasSize(1);
        assertThat(outboxRepository.findByExecutionIdOrderBySequenceAsc(execution.getId()))
                .hasSize(1);
    }

    @Test
    @DisplayName("identical CANCEL retry AFTER the execution went terminal returns the SAME receipt, not 409")
    void identicalCancelRetryAfterTerminalReplaysSameRecord() {
        UUID clientRequestId = UUID.randomUUID();
        ControlReceipt first = cancelConfirmed(clientRequestId);

        // Live-row terminal stamp (the first cancel already inserted an
        // outbox row; the stale seed's cursor=0 must not be re-saved).
        tx(() -> {
            SessionExecution locked = executions.findWithLockById(execution.getId())
                    .orElseThrow();
            locked.setState(SessionExecution.State.CANCELLED);
            locked.setTerminalMessageId("tm-cancelled");
            executions.save(locked);
        });

        ControlReceipt replay = cancelConfirmed(clientRequestId);
        assertThat(replay.controlRequestId()).isEqualTo(first.controlRequestId());
        assertThat(replay.status()).isEqualTo(first.status());
        assertThat(rowsByCreated(execution.getId())).hasSize(1);
    }

    @Test
    @DisplayName("same clientRequestId with DIFFERENT bytes → 409 DUPLICATE_CODE")
    void sameClientRequestIdDifferentBytesFails() {
        UUID clientRequestId = UUID.randomUUID();
        control(editor(), clientRequestId, "HOLD");
        assertThatThrownBy(() -> control(editor(), clientRequestId, "CONTINUE"))
                .isInstanceOf(DuplicateResourceException.class);
        assertThat(rowsByCreated(execution.getId())).hasSize(1);
    }

    @Test
    @DisplayName("viewer denied (403); platform-only admin denied (separation of duties)")
    void viewerAndPlatformOnlyAdminDenied() {
        var viewer = principal(viewerUser.getId(),
                List.of("proj:" + project.getId() + ":VIEWER"));
        assertThatThrownBy(() -> control(viewer, UUID.randomUUID(), "HOLD"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        // PLATFORM_ADMIN alone does NOT imply data access (§4/ UserRole javadoc).
        var platformOnly = principal(editorUser.getId(), List.of("sys:PLATFORM_ADMIN"));
        assertThatThrownBy(() -> control(platformOnly, UUID.randomUUID(), "HOLD"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        // Even carrying system AND project claims, the authorization is the
        // EDITOR claim (the evaluator resolves claims; the granted rows back
        // token minting) — the extra PLATFORM_ADMIN claim neither grants nor
        // revokes data access (§4: PLATFORM_ADMIN alone implies nothing on
        // the data axis).
        var editorWithPlatformClaim = principal(editorUser.getId(), List.of("sys:PLATFORM_ADMIN",
                "proj:" + project.getId() + ":EDITOR"));
        ControlReceipt allowed = control(editorWithPlatformClaim, UUID.randomUUID(), "HOLD");
        assertThat(allowed.status()).isEqualTo("ACCEPTED");

        // A VIEWER-only claim set stays denied even with PLATFORM_ADMIN
        // alongside (no EDITOR-equivalent claim anywhere).
        var viewerWithPlatformClaim = principal(viewerUser.getId(), List.of("sys:PLATFORM_ADMIN",
                "proj:" + project.getId() + ":VIEWER"));
        assertThatThrownBy(() -> control(viewerWithPlatformClaim, UUID.randomUUID(), "HOLD"))
                .as("PLATFORM_ADMIN + VIEWER claims grant no EDITOR privilege")
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        // Only the one ALLOWED intent row exists (the denied calls wrote
        // nothing).
        assertThat(rowsByCreated(execution.getId())).hasSize(1);
    }

    @Test
    @DisplayName("foreign attempt (ownership-chain mismatch) → 404 before any host lookup")
    void foreignAttemptFails404() {
        ExecutionScope bad = new ExecutionScope(project.getId(), workflow.getId(),
                request.getId(), task.getId(),
                UUID.randomUUID(), execution.getId());
        assertThatThrownBy(() -> service.control(bad, editor(),
                new ControlRequest(UUID.randomUUID(), "HOLD")))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(rowsByCreated(execution.getId())).isEmpty();
    }

    @Test
    @DisplayName("CONTINUE on a durable PAUSED execution is rejected (409 EXECUTION_TERMINAL)")
    void continueOnDurablePausedIsRejected() {
        // No prior allocation on this execution — the seed entity is current.
        execution.setState(SessionExecution.State.PAUSED);
        execution.setTerminalMessageId("tm-paused");
        executions.saveAndFlush(execution);

        assertThatThrownBy(() -> control(editor(), UUID.randomUUID(), "CONTINUE"))
                .isInstanceOf(ResourceInUseException.class)
                .hasMessageContaining("EXECUTION_TERMINAL");
    }

    @Test
    @DisplayName("terminal race: control on COMPLETED/FAILED/CANCELLED execution rejected")
    void controlOnTerminalRejected() {
        // No prior allocation on this execution — the seed entity is current.
        execution.setState(SessionExecution.State.COMPLETED);
        execution.setTerminalMessageId("tm-done");
        executions.saveAndFlush(execution);

        assertThatThrownBy(() -> control(editor(), UUID.randomUUID(), "HOLD"))
                .isInstanceOf(ResourceInUseException.class)
                .hasMessageContaining("EXECUTION_TERMINAL");
    }

    @Test
    @DisplayName("revision allocation: two distinct controls issue 1 then 2 under the row lock")
    void revisionsAllocateSequentiallyUnderLock() {
        ControlReceipt first = control(editor(), UUID.randomUUID(), "HOLD");
        ControlReceipt second = control(editor(), UUID.randomUUID(), "CONTINUE");
        assertThat(first.controlRevision()).isEqualTo(1L);
        assertThat(second.controlRevision()).isEqualTo(2L);
        assertThat(rowsByCreated(execution.getId()))
                .extracting(ExecutionControlRequest::getControlRevision)
                .containsExactly(1L, 2L);
    }

    // ------------------------------------------------------------------
    // Chat proposals (§22.7): propose/decide + confirmation flow
    // ------------------------------------------------------------------

    @Test
    @DisplayName("chat CANCEL proposal → CONFIRMATION_REQUIRED + no command + 120s engine-stamped expiry")
    void chatCancelRequiresConfirmationWithNoCommand() {
        admittedInteraction();
        ProposalReceipt receipt =
                service.propose(scope(), chatPayload(ExecutionControlRequestPayload.Action.CANCEL));

        assertThat(receipt.status()).isEqualTo("CONFIRMATION_REQUIRED");
        assertThat(receipt.expiresAt()).isNotNull();
        assertThat(receipt.expiresAt()).isAfter(Instant.now().plusSeconds(110));
        assertThat(receipt.commandMessageId()).as("NO command before confirm").isNull();

        ExecutionControlRequest row = rowById(receipt.controlRequestId());
        assertThat(row.getStatus()).isEqualTo(InteractionControlStatus.CONFIRMATION_REQUIRED);
        assertThat(row.getActorUserId()).isEqualTo(editorUser.getId());
        assertThat(row.getOrigin()).isEqualTo("CHAT");
        // Nano-precision round-trips through H2 at micros: compare with a
        // 10ms tolerance instead of exact equality.
        assertThat(row.getConfirmationExpiresAt())
                .isCloseTo(receipt.expiresAt(),
                        org.assertj.core.api.Assertions.within(10, java.time.temporal.ChronoUnit.MILLIS));
        assertThat(row.getCommandMessageId()).isNull();
        assertThat(outboxRepository.findByExecutionIdOrderBySequenceAsc(execution.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("confirm on a pending chat CANCEL issues ONE execution.cancel (existing cancel path)")
    void confirmIssuesOneCancelCommand() {
        admittedInteraction();
        ProposalReceipt proposal =
                service.propose(scope(), chatPayload(ExecutionControlRequestPayload.Action.CANCEL));

        ProposalReceipt decided = service.decide(scope(), proposal.controlRequestId(),
                editor(), new Decision("CONFIRM"));

        assertThat(decided.status()).isEqualTo("ACCEPTED");
        assertThat(decided.commandMessageId()).isNotNull();

        ExecutionControlRequest row = rowById(proposal.controlRequestId());
        assertThat(row.getStatus()).isEqualTo(InteractionControlStatus.ACCEPTED);
        assertThat(row.getConfirmedBy()).isEqualTo(editorUser.getId());
        assertThat(row.getCommandMessageId()).isNotNull();
        assertThat(row.getDecidedAt()).isNotNull();

        List<ExecutionCommandOutbox> outbox = outboxRepository
                .findByExecutionIdOrderBySequenceAsc(execution.getId());
        assertThat(outbox).hasSize(1);
        ExecutionCommandOutbox entry = outbox.get(0);
        assertThat(entry.getId().toString()).isEqualTo(row.getCommandMessageId());
        assertThat(entry.getType()).isEqualTo("execution.cancel");
        Map<String, Object> cancelPayload = payloadOf(entry);
        assertThat(cancelPayload.get("executionId")).isEqualTo(execution.getId().toString());
        assertThat(cancelPayload.get("dispatchId")).isEqualTo(attempt.getId().toString());
        assertThat(cancelPayload.get("reasonCode")).isEqualTo("USER_REQUESTED");
        assertThat(((Number) cancelPayload.get("gracePeriodSeconds")).intValue()).isEqualTo(5);
    }

    @Test
    @DisplayName("simultaneous/serial confirms issue ONE command (row lock + idempotent decide)")
    void simultaneousConfirmsIssueOne() throws Exception {
        admittedInteraction();
        ProposalReceipt proposal =
                service.propose(scope(), chatPayload(ExecutionControlRequestPayload.Action.CANCEL));

        // Idempotent replay: a second confirm returns the same disposition.
        ProposalReceipt first = service.decide(scope(), proposal.controlRequestId(),
                editor(), new Decision("CONFIRM"));
        ProposalReceipt second = service.decide(scope(), proposal.controlRequestId(),
                editor(), new Decision("CONFIRM"));
        assertThat(second.commandMessageId()).isEqualTo(first.commandMessageId());
        assertThat(second.resolutionRevision()).isEqualTo(first.resolutionRevision());

        // A concurrent PAIR of REQUIRES_NEW deciders also collapses to one
        // command — both contend on the row lock; the loser replays idempotently.
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            Runnable contender = () -> {
                try {
                    ready.countDown();
                    release.await();
                    txTemplate().executeWithoutResult(s ->
                            service.decide(scope(), proposal.controlRequestId(),
                                    editor(), new Decision("CONFIRM")));
                    completed.incrementAndGet();
                } catch (Exception ignored) {
                }
            };
            pool.submit(contender);
            pool.submit(contender);
            assertThat(ready.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(completed.get()).isEqualTo(2);
        assertThat(outboxRepository.findByExecutionIdOrderBySequenceAsc(execution.getId()))
                .as("exactly ONE cancel command across all confirms").hasSize(1);
    }

    @Test
    @DisplayName("decline creates no command; row DECLINED")
    void declineCreatesNoCommand() {
        admittedInteraction();
        ProposalReceipt proposal =
                service.propose(scope(), chatPayload(ExecutionControlRequestPayload.Action.CANCEL));

        ProposalReceipt decided = service.decide(scope(), proposal.controlRequestId(),
                editor(), new Decision("DECLINE"));

        assertThat(decided.status()).isEqualTo("DECLINED");
        assertThat(decided.commandMessageId()).isNull();
        assertThat(rowById(proposal.controlRequestId()).getStatus())
                .isEqualTo(InteractionControlStatus.DECLINED);
        assertThat(outboxRepository.findByExecutionIdOrderBySequenceAsc(execution.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("decide past the engine-stamped expiry → settled EXPIRED disposition, no command (the boundary maps it to 409)")
    void decideOnExpiredConfirmationFails() {
        admittedInteraction();
        ProposalReceipt proposal =
                service.propose(scope(), chatPayload(ExecutionControlRequestPayload.Action.CANCEL));

        ageConfirmationExpiry(proposal.controlRequestId(), -5);

        // The service settles the row EXPIRED atomically (a settled
        // disposition, not an exception — the controller maps the EXPIRED
        // receipt to the §4 409 reasonCode CONFIRMATION_EXPIRED).
        ProposalReceipt decided = service.decide(scope(), proposal.controlRequestId(),
                editor(), new Decision("CONFIRM"));
        assertThat(decided.status()).isEqualTo("EXPIRED");
        assertThat(decided.errorCode()).isEqualTo("CONFIRMATION_EXPIRED");
        assertThat(decided.commandMessageId()).isNull();
        assertThat(rowById(proposal.controlRequestId()).getStatus())
                .isEqualTo(InteractionControlStatus.EXPIRED);
        assertThat(outboxRepository.findByExecutionIdOrderBySequenceAsc(execution.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("revoked actor denied on confirm — decide rechecks the CURRENT actor, not a bearer claim")
    void decideRechecksCurrentActor() {
        admittedInteraction();
        ProposalReceipt proposal =
                service.propose(scope(), chatPayload(ExecutionControlRequestPayload.Action.CANCEL));

        var viewer = principal(viewerUser.getId(),
                List.of("proj:" + project.getId() + ":VIEWER"));
        assertThatThrownBy(() -> service.decide(scope(), proposal.controlRequestId(),
                viewer, new Decision("CONFIRM")))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(rowById(proposal.controlRequestId()).getStatus())
                .isEqualTo(InteractionControlStatus.CONFIRMATION_REQUIRED);
    }

    @Test
    @DisplayName("CONFIRM after the execution went terminal → REJECTED/EXECUTION_TERMINAL, no command, no outbox")
    void confirmAfterTerminalSettlesRejectedNoCommand() {
        admittedInteraction();
        ProposalReceipt proposal =
                service.propose(scope(), chatPayload(ExecutionControlRequestPayload.Action.CANCEL));

        // Live-row terminal stamp (the admission's accepted event already
        // advanced the §3.5 cursor; the stale seed has cursor 0).
        tx(() -> {
            SessionExecution locked = executions.findWithLockById(execution.getId())
                    .orElseThrow();
            locked.setState(SessionExecution.State.COMPLETED);
            locked.setTerminalMessageId("tm-early");
            executions.save(locked);
        });

        ProposalReceipt decided = service.decide(scope(), proposal.controlRequestId(),
                editor(), new Decision("CONFIRM"));

        assertThat(decided.status()).isEqualTo("REJECTED");
        assertThat(decided.errorCode()).isEqualTo("EXECUTION_TERMINAL");
        assertThat(decided.commandMessageId()).as("no command on a terminal execution")
                .isNull();
        assertThat(decided.resolutionRevision()).isEqualTo(proposal.resolutionRevision());

        ExecutionControlRequest row = rowById(proposal.controlRequestId());
        assertThat(row.getStatus()).isEqualTo(InteractionControlStatus.REJECTED);
        assertThat(row.getConfirmedBy()).isEqualTo(editorUser.getId());
        assertThat(row.getDecidedAt()).isNotNull();
        assertThat(outboxRepository.findByExecutionIdOrderBySequenceAsc(execution.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("chat HOLD proposal derives actor from the interaction; ACCEPTED with command + revision")
    void chatHoldProposalAcceptsWithCommand() {
        admittedInteraction();
        ProposalReceipt receipt =
                service.propose(scope(), chatPayload(ExecutionControlRequestPayload.Action.HOLD));

        assertThat(receipt.status()).isEqualTo("ACCEPTED");
        assertThat(receipt.controlRevision()).isEqualTo(1L);
        assertThat(receipt.commandMessageId()).isNotNull();
        ExecutionControlRequest row = rowById(receipt.controlRequestId());
        assertThat(row.getActorUserId()).isEqualTo(editorUser.getId());
        assertThat(row.getOrigin()).isEqualTo("CHAT");
        assertThat(rowsByCreated(execution.getId())).hasSize(1);
        assertThat(outboxRepository.findByExecutionIdOrderBySequenceAsc(execution.getId()))
                .hasSize(1);
    }

    @Test
    @DisplayName("duplicate proposal ID (same execution + ID) replays the stored disposition")
    void duplicateProposalIdReplays() {
        admittedInteraction();
        var payload = chatPayload(ExecutionControlRequestPayload.Action.CANCEL);
        ProposalReceipt first = service.propose(scope(), payload);
        ProposalReceipt replay = service.propose(scope(), payload);
        assertThat(replay.controlRequestId()).isEqualTo(first.controlRequestId());
        assertThat(replay.status()).isEqualTo(first.status());
        assertThat(replay.resolutionRevision()).isEqualTo(first.resolutionRevision());
        assertThat(rowsByCreated(execution.getId())).hasSize(1);
    }

    @Test
    @DisplayName("proposal for an interaction that is no longer pending → REJECTED/INVALID_INTERACTION")
    void proposalForSettledInteractionRefused() {
        admittedInteraction();
        settleInteraction();

        ProposalReceipt receipt = service.propose(scope(),
                chatPayload(ExecutionControlRequestPayload.Action.CANCEL));
        assertThat(receipt.status()).isEqualTo("REJECTED");
        assertThat(receipt.errorCode()).isEqualTo("INVALID_INTERACTION");
        assertThat(rowById(receipt.controlRequestId()).getStatus())
                .isEqualTo(InteractionControlStatus.REJECTED);
        assertThat(outboxRepository.findByExecutionIdOrderBySequenceAsc(execution.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("proposal on a terminal execution → REJECTED/EXECUTION_TERMINAL")
    void proposalOnTerminalRejected() {
        admittedInteraction();
        tx(() -> {
            SessionExecution locked = executions.findWithLockById(execution.getId())
                    .orElseThrow();
            locked.setState(SessionExecution.State.CANCELLED);
            locked.setTerminalMessageId("tm-x");
            executions.save(locked);
        });

        ProposalReceipt receipt = service.propose(scope(),
                chatPayload(ExecutionControlRequestPayload.Action.HOLD));
        assertThat(receipt.status()).isEqualTo("REJECTED");
        assertThat(receipt.errorCode()).isEqualTo("EXECUTION_TERMINAL");
    }

    @Test
    @DisplayName("revoked actor's proposal → REJECTED/FORBIDDEN from GRANT ROWS, no command, no outbox")
    void revokedEditorProposalRejectedForbidden() {
        admittedInteraction();
        // The §3.5 cursor advanced only via the admission's accepted EVENT
        // (the outbox insert never touches it — Fix 1).
        long cursorAfterAdmit = currentExecution().getStreamSequence();

        tx(() -> userRoleRepository.deleteAll(
                userRoleRepository.findByUserIdAndProjectId(editorUser.getId(),
                        project.getId())));

        ProposalReceipt receipt = service.propose(scope(),
                chatPayload(ExecutionControlRequestPayload.Action.HOLD));

        assertThat(receipt.status()).isEqualTo("REJECTED");
        assertThat(receipt.errorCode()).isEqualTo("FORBIDDEN");
        ExecutionControlRequest row = rowById(receipt.controlRequestId());
        assertThat(row.getStatus()).isEqualTo(InteractionControlStatus.REJECTED);
        assertThat(row.getErrorCode()).isEqualTo("FORBIDDEN");
        // Resolution revision bumped (§22.7: revisions increase per
        // disposition), and NO command was ever dispatched.
        assertThat(row.getResolutionRevision()).isEqualTo(1L);
        assertThat(row.getCommandMessageId()).as("no command for a revoked actor").isNull();
        assertThat(outboxRepository.findByExecutionIdOrderBySequenceAsc(execution.getId()))
                .isEmpty();
        // The execution's stream cursor did NOT advance (no event row was
        // written; the rejected proposal inserts neither outbox nor event).
        assertThat(currentExecution().getStreamSequence()).isEqualTo(cursorAfterAdmit);
    }

    @Test
    @DisplayName("still-granted actor's proposal stays ACCEPTED (grant-row recheck passes)")
    void grantedEditorProposalStillAccepted() {
        admittedInteraction();
        ProposalReceipt receipt = service.propose(scope(),
                chatPayload(ExecutionControlRequestPayload.Action.CONTINUE));
        assertThat(receipt.status()).isEqualTo("ACCEPTED");
        assertThat(receipt.errorCode()).isNull();
    }

    @Test
    @DisplayName("DEACTIVATED actor (grants intact) → REJECTED/FORBIDDEN — user-active parity with the JWT path")
    void deactivatedActorProposalRejectedForbidden() {
        admittedInteraction();

        // Deactivate the user WITHOUT touching the grant rows: the live-JWT
        // path denies at the authentication filter, so the grant-row recheck
        // must deny too (parity), else a deactivated user could still
        // dispatch through their stale admitted interaction.
        tx(() -> {
            ai.myrmec.engine.user.User row =
                    userRepository.findById(editorUser.getId()).orElseThrow();
            row.setIsActive(false);
            userRepository.save(row);
        });

        ProposalReceipt receipt = service.propose(scope(),
                chatPayload(ExecutionControlRequestPayload.Action.HOLD));

        assertThat(receipt.status()).isEqualTo("REJECTED");
        assertThat(receipt.errorCode()).isEqualTo("FORBIDDEN");
        assertThat(rowById(receipt.controlRequestId()).getCommandMessageId())
                .as("no command for a deactivated actor").isNull();
        assertThat(outboxRepository.findByExecutionIdOrderBySequenceAsc(execution.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("proposal with a FOREIGN dispatchId → REJECTED/INVALID_INTERACTION (exact-attempt check)")
    void proposalWithForeignDispatchIdRejected() {
        admittedInteraction();
        var payload = new ExecutionControlRequestPayload(execution.getId(),
                UUID.randomUUID(), interaction.getId(), UUID.randomUUID(),
                ExecutionControlRequestPayload.Action.HOLD, "wrong attempt");
        ProposalReceipt receipt = service.propose(scope(), payload);
        assertThat(receipt.status()).isEqualTo("REJECTED");
        assertThat(receipt.errorCode()).isEqualTo("INVALID_INTERACTION");
        assertThat(outboxRepository.findByExecutionIdOrderBySequenceAsc(execution.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("group-scoped EDITOR grant qualifies the derived actor (ancestor cascade via grant rows)")
    void groupScopedEditorGrantQualifiesActor() {
        admittedInteraction();
        // The project sits in the DEFAULT group tree; a GROUP-scoped EDITOR
        // on the project's own group must satisfy the row-based recheck
        // (ProjectAccessEvaluator's ancestor walk).
        UUID group = projectRepository.findById(project.getId()).orElseThrow().getGroupId();
        UserRole groupGrant = new UserRole();
        groupGrant.setUserId(editorUser.getId());
        groupGrant.setRole(UserRole.Role.EDITOR);
        groupGrant.setScopeType(UserRole.ScopeType.GROUP);
        groupGrant.setGroupId(group);
        groupGrant.setGrantedByUserId(TEST_ADMIN_ID);
        userRoleRepository.save(groupGrant);

        tx(() -> userRoleRepository.deleteAll(
                userRoleRepository.findByUserIdAndProjectId(editorUser.getId(),
                        project.getId())));

        ProposalReceipt receipt = service.propose(scope(),
                chatPayload(ExecutionControlRequestPayload.Action.CONTINUE));
        assertThat(receipt.status()).as("group grant cascades to the project")
                .isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("sweeper expires PENDING chat-CANCELs past expiry; never resets execution deadlines")
    void sweeperExpiresPastConfirmations() {
        admittedInteraction();
        ProposalReceipt proposal =
                service.propose(scope(), chatPayload(ExecutionControlRequestPayload.Action.CANCEL));

        ageConfirmationExpiry(proposal.controlRequestId(), -30);
        Instant deadlineBefore = currentExecution().getDeadline();

        int swept = sweeper.sweepOnce(Instant.now());

        assertThat(swept).isEqualTo(1);
        ExecutionControlRequest row = rowById(proposal.controlRequestId());
        assertThat(row.getStatus()).isEqualTo(InteractionControlStatus.EXPIRED);
        assertThat(row.getCommandMessageId()).as("expiry issues no command").isNull();
        assertThat(row.getDecidedAt()).isNotNull();
        assertThat(currentExecution().getDeadline()).as("sweep never resets deadlines")
                .isEqualTo(deadlineBefore);
        assertThat(outboxRepository.findByExecutionIdOrderBySequenceAsc(execution.getId()))
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // Dispatch semantics (§3.4): the stored envelope, not a rebuilt one
    // ------------------------------------------------------------------

    @Test
    @DisplayName("post-commit dispatch sends the EXACT stored envelope with messageId == row id")
    void dispatchAfterCommitSendsStoredEnvelopeBytes() {
        control(editor(), UUID.randomUUID(), "HOLD");
        ExecutionCommandOutbox entry = outboxRepository
                .findByExecutionIdOrderBySequenceAsc(execution.getId()).get(0);

        var captured = new java.util.ArrayList<String>();
        dispatcher.sendNow(entry.getId(), json -> {
            captured.add(json);
            return true;
        });
        assertThat(captured).hasSize(1);
        assertThat(captured.get(0)).contains("\"messageId\":\"" + entry.getId() + "\"");
        assertThat(captured.get(0)).contains("execution.control");
        // The stored envelope's payload identity is preserved verbatim
        // (messageId + payload; never rebuilt or reminted).
        assertThat(captured.get(0)).contains("\"controlRevision\":1");
        assertThat(captured.get(0)).contains("\"action\":\"HOLD\"");
        // Fix E minor: a FAILED sendNow reports 0 (1 only on a successful
        // send); the row stays PENDING for retransmission either way.
        assertThat(dispatcher.sendNow(entry.getId(), json2 -> false)).isEqualTo(0);
        ExecutionCommandOutbox afterFailed = outboxRepository.findById(entry.getId()).orElseThrow();
        assertThat(afterFailed.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(afterFailed.getDeliveryCount()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("send failure keeps the outbox PENDING; retransmission reuses the stored envelope")
    void sendFailureKeepsPendingAndRetransmits() {
        control(editor(), UUID.randomUUID(), "HOLD");
        ExecutionCommandOutbox entry = outboxRepository
                .findByExecutionIdOrderBySequenceAsc(execution.getId()).get(0);

        // First dispatch attempt fails (channel down) — row stays PENDING.
        // (The post-commit delivery already advanced the count to >=1; a
        // FAILED attempt reports 0 per the Fix E seam contract.)
        assertThat(dispatcher.sendNow(entry.getId(), json -> false)).isEqualTo(0);
        ExecutionCommandOutbox afterFailure = outboxRepository
                .findById(entry.getId()).orElseThrow();
        assertThat(afterFailure.getStatus()).isEqualTo(OutboxStatus.PENDING);

        // The channel returns: retransmit the EXACT stored envelope.
        var captured = new java.util.ArrayList<String>();
        assertThat(dispatcher.sendNow(entry.getId(), s -> {
            captured.add(s);
            return true;
        })).isEqualTo(1);
        assertThat(captured).hasSize(1);
        assertThat(captured.get(0)).contains("\"messageId\":\"" + entry.getId() + "\"");
        // delivery_count advanced beyond the post-commit attempt and the
        // failed explicit attempt; the status stays PENDING until an ack.
        ExecutionCommandOutbox after = outboxRepository.findById(entry.getId()).orElseThrow();
        assertThat(after.getDeliveryCount()).isGreaterThanOrEqualTo(3);
        assertThat(after.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("dispatcher polls ONLY due PENDING rows (never ACKED/EXPIRED/future)")
    void dispatcherDueFilters() {
        control(editor(), UUID.randomUUID(), "HOLD");
        ExecutionCommandOutbox entry = outboxRepository
                .findByExecutionIdOrderBySequenceAsc(execution.getId()).get(0);

        // Push nextDeliveryAt into the future → not picked.
        txTemplate().executeWithoutResult(s -> {
            ExecutionCommandOutbox row = outboxRepository.findById(entry.getId()).orElseThrow();
            row.setNextDeliveryAt(Instant.now().plusSeconds(300));
            outboxRepository.save(row);
        });
        dispatcher.dispatchDue(Instant.now());
        long countAfterFuture = outboxRepository.findById(entry.getId()).orElseThrow()
                .getDeliveryCount();
        assertThat(outboxRepository.findById(entry.getId()).orElseThrow()
                .getNextDeliveryAt()).isAfter(Instant.now());

        // Back in the past → picked once (the ack seam lives with Task 8/10;
        // the sweep advances delivery_count, re-stamps the backoff, and keeps
        // the status PENDING).
        txTemplate().executeWithoutResult(s -> {
            ExecutionCommandOutbox row = outboxRepository.findById(entry.getId()).orElseThrow();
            row.setNextDeliveryAt(Instant.now().minusSeconds(1));
            outboxRepository.save(row);
        });
        dispatcher.dispatchDue(Instant.now());
        ExecutionCommandOutbox after = outboxRepository.findById(entry.getId()).orElseThrow();
        assertThat(after.getDeliveryCount()).isGreaterThan((int) countAfterFuture);
        // Paced: the sweep re-stamped nextDeliveryAt into the future.
        assertThat(after.getNextDeliveryAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("retransmission pacing: escalating 2^n backoff capped at 30s on every advance")
    void retransmissionEscalatingBackoff() {
        control(editor(), UUID.randomUUID(), "HOLD");
        ExecutionCommandOutbox entry = outboxRepository
                .findByExecutionIdOrderBySequenceAsc(execution.getId()).get(0);

        // Attempt 2 → 2^2=4s (the post-commit attempt was count 1).
        dispatcher.sendNow(entry.getId(), json -> false);
        ExecutionCommandOutbox afterFirst = outboxRepository.findById(entry.getId()).orElseThrow();
        assertThat(afterFirst.getDeliveryCount()).isEqualTo(2);
        java.time.Duration gap1 = java.time.Duration.between(Instant.now(),
                afterFirst.getNextDeliveryAt());
        assertThat(gap1).isBetween(java.time.Duration.ofSeconds(3),
                java.time.Duration.ofSeconds(6));

        // Attempt 3 → 8s; attempt 4 → 16s.
        dispatcher.sendNow(entry.getId(), json -> false);
        java.time.Duration gap2 = java.time.Duration.between(Instant.now(),
                outboxRepository.findById(entry.getId()).orElseThrow().getNextDeliveryAt());
        assertThat(gap2).isBetween(java.time.Duration.ofSeconds(7),
                java.time.Duration.ofSeconds(10));

        dispatcher.sendNow(entry.getId(), json -> false);
        java.time.Duration gap3 = java.time.Duration.between(Instant.now(),
                outboxRepository.findById(entry.getId()).orElseThrow().getNextDeliveryAt());
        assertThat(gap3).isBetween(java.time.Duration.ofSeconds(15),
                java.time.Duration.ofSeconds(18));

        // Cap: repeatedly attempt; the gap never exceeds 30s (+jitter margin).
        for (int i = 0; i < 6; i++) {
            dispatcher.sendNow(entry.getId(), json -> false);
        }
        java.time.Duration gapCap = java.time.Duration.between(Instant.now(),
                outboxRepository.findById(entry.getId()).orElseThrow().getNextDeliveryAt());
        assertThat(gapCap).isBetween(java.time.Duration.ofSeconds(29),
                java.time.Duration.ofSeconds(31));
        assertThat(outboxRepository.findById(entry.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("pacing re-stamps after FAILED sends too — failed sendNow reports 0, paces the row")
    void failedSendStillAdvancesCountAndPace() {
        control(editor(), UUID.randomUUID(), "HOLD");
        ExecutionCommandOutbox entry = outboxRepository
                .findByExecutionIdOrderBySequenceAsc(execution.getId()).get(0);

        assertThat(dispatcher.sendNow(entry.getId(), json -> false))
                .as("failed send is not a success").isEqualTo(0);
        ExecutionCommandOutbox after = outboxRepository.findById(entry.getId()).orElseThrow();
        assertThat(after.getDeliveryCount())
                .as("count advanced past the post-commit attempt AND this one")
                .isGreaterThanOrEqualTo(2);
        assertThat(after.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(after.getNextDeliveryAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("ack-before-state: the receipt is handed back at commit — PENDING outbox is not an error")
    void receiptReturnedOnCommitNotOnAck() {
        ControlReceipt receipt = control(editor(), UUID.randomUUID(), "HOLD");
        assertThat(receipt).isNotNull();
        ExecutionCommandOutbox entry = outboxRepository
                .findByExecutionIdOrderBySequenceAsc(execution.getId()).get(0);
        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void admittedInteraction() {
        interaction = new ExecutionInteraction();
        interaction.setExecutionId(execution.getId());
        interaction.setOrdinal(1L);
        interaction.setActorUserId(editorUser.getId());
        interaction.setClientRequestId(UUID.randomUUID());
        interaction.setRequestDigest("a".repeat(64));
        interaction.setStatus(InteractionStatus.ACCEPTED);
        interaction.setRequestText("cancel my attempt please");
        interaction.setResponseDeadline(Instant.now().plusSeconds(120)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        interaction.setAcceptedAt(Instant.now()
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        interaction.setUsageStatus("UNKNOWN");
        interactions.saveAndFlush(interaction);
        // The pending pointer marks this as THE admitted chat (§3.2).
        tx(() -> {
            SessionExecution locked = executions.findWithLockById(execution.getId()).orElseThrow();
            locked.setPendingInteractionId(interaction.getId());
            executions.save(locked);
        });
    }

    private void settleInteraction() {
        tx(() -> {
            ExecutionInteraction row = interactions.findWithLockById(interaction.getId())
                    .orElseThrow();
            row.setStatus(InteractionStatus.COMPLETED);
            row.setAnswerText("done");
            row.setCompletedAt(Instant.now());
            row.setUsageStatus("KNOWN");
            interactions.save(row);
            SessionExecution locked = executions.findWithLockById(execution.getId()).orElseThrow();
            locked.setPendingInteractionId(null);
            executions.save(locked);
        });
    }

    private void ageConfirmationExpiry(UUID requestId, long seconds) {
        tx(() -> {
            ExecutionControlRequest row = controlRequests.findWithLockById(requestId)
                    .orElseThrow();
            row.setConfirmationExpiresAt(Instant.now().plusSeconds(seconds)
                    .truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
            controlRequests.save(row);
        });
    }

    private ExecutionControlRequestPayload chatPayload(
            ExecutionControlRequestPayload.Action action) {
        return new ExecutionControlRequestPayload(execution.getId(), attempt.getId(),
                interaction.getId(), UUID.randomUUID(), action,
                "User asked to stop this attempt.");
    }

    private ControlReceipt control(UserPrincipal actor, UUID clientRequestId, String action) {
        return service.control(scope(), actor, new ControlRequest(clientRequestId, action));
    }

    private ControlReceipt cancelConfirmed(UUID clientRequestId) {
        return service.cancel(scope(), editor(), new ConfirmedCancelRequest(clientRequestId, true));
    }

    private UserPrincipal editor() {
        return principal(editorUser.getId(),
                List.of("proj:" + project.getId() + ":EDITOR"));
    }

    private UserPrincipal principal(UUID userId, List<String> roles) {
        return new UserPrincipal(userId, "Test User",
                "user-" + userId.toString().substring(0, 8) + "@test.local", roles);
    }

    private TransactionTemplate txTemplate() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private void tx(Runnable body) {
        txTemplate().executeWithoutResult(s -> body.run());
    }

    private List<ExecutionControlRequest> rowsByCreated(UUID executionId) {
        return controlRequests.findByExecutionIdOrderByCreatedAtAsc(executionId);
    }

    private ExecutionControlRequest rowById(UUID requestId) {
        return controlRequests.findById(requestId).orElseThrow();
    }

    private SessionExecution currentExecution() {
        return executions.findById(execution.getId()).orElseThrow();
    }

    private ExecutionScope scope() {
        return new ExecutionScope(project.getId(), workflow.getId(), request.getId(),
                task.getId(), attempt.getId(), execution.getId());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payloadOf(ExecutionCommandOutbox entry) {
        Object payload = entry.getEnvelope().get("payload");
        return payload instanceof Map<?, ?> nested ? (Map<String, Object>) nested : Map.of();
    }

    private User seedUser(String suffix) {
        User user = new User();
        user.setEmail("ctrl-" + suffix + "-" + System.nanoTime() + "@test.local");
        user.setName("Ctrl " + suffix);
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
        wf.setName("ctrl-wf-" + System.nanoTime());
        wf.setSteps(List.of());
        wf.setVersion(1);
        wf.setStatus(WorkflowStatus.PUBLISHED);
        wf.setCreatedBy(userRepository.findById(TEST_ADMIN_ID).orElseThrow());
        return workflowRepository.save(wf);
    }
}
