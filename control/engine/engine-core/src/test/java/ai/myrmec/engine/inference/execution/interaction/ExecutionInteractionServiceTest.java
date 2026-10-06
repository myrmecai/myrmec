// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine._system.exception.BadRequestException;
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
import ai.myrmec.engine.websocket.host.payload.ExecutionInteractionCompletePayload;
import ai.myrmec.engine.websocket.host.payload.ExecutionInteractionFailedPayload;
import ai.myrmec.engine.workflow.ExecutionEvent;
import ai.myrmec.engine.workflow.ExecutionEventRepository;
import ai.myrmec.engine.workflow.EventType;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Task 8 (plan 2026-10-03-session-interaction): the admission service
 * (POST /interactions), the inbound outcome arms (complete/failed), the
 * boundary/identity rules, the terminal races, and the retention sweeper.
 * Wire authority: protocol §22.3/§22.6/§22.8; API authority: plan section 4.
 */
class ExecutionInteractionServiceTest extends IntegrationTestBase {

    @Autowired ExecutionInteractionService service;
    @Autowired InteractionUsageService usageService;
    @Autowired InteractionRetentionSweeper sweeper;
    @Autowired ExecutionCommandOutboxDispatcher dispatcher;
    @Autowired ExecutionControlService controlService;
    @Autowired TestDataBuilder data;
    @Autowired SessionRepository sessionRepository;
    @Autowired SessionExecutionRepository executions;
    @Autowired ExecutionInteractionRepository interactions;
    @Autowired ExecutionControlRequestRepository controlRequests;
    @Autowired ExecutionCommandOutboxRepository outboxRepository;
    @Autowired ExecutionEventRepository executionEventRepository;
    @Autowired WorkflowRequestRepository requestRepository;
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
    private User editorUser;
    private User viewerUser;

    @BeforeEach
    void seed() {
        project = data.project().named("adm-proj").create();
        workflow = workflowOf(project);
        User admin = userRepository.findById(TEST_ADMIN_ID).orElseThrow();

        WorkflowRequest req = new WorkflowRequest();
        req.setWorkflow(workflow);
        req.setWorkflowVersion(1);
        req.setInput(Map.of());
        req.setStatus(RequestStatus.RUNNING);
        req.setBranch("myrmec/adm");
        req.setCreatedBy(admin);
        req.setCreatedAt(Instant.now());
        request = requestRepository.save(req);

        var profile = data.agentProfile().named("adm-profile").create();
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

        AgentHostInstance instance = instances.saveAndFlush(AgentHostInstance.open(
                data.agent().named("adm-host").create().agent(), null,
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
    // Admission (§4 POST /interactions + §22.6)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("admit: returns 202-shaped InteractionAdmission; ordinal 1; deadline = min(+timeout, execution deadline)")
    void admitReturnsAdmissionAndPersistsRow() {
        InteractionAdmission admission = service.admit(scope(), editor(),
                UUID.randomUUID(), "Why is the verifier slow?");

        assertThat(admission.interactionId()).isNotNull();
        assertThat(admission.ordinal()).isEqualTo(1L);
        assertThat(admission.status()).isEqualTo(InteractionStatus.ACCEPTED);
        assertThat(admission.responseDeadline())
                .isAfter(Instant.now().plusSeconds(110))
                .isBefore(execution.getDeadline().plusSeconds(1));

        ExecutionInteraction row = interactions.findById(admission.interactionId())
                .orElseThrow();
        assertThat(row.getExecutionId()).isEqualTo(execution.getId());
        assertThat(row.getActorUserId()).isEqualTo(editorUser.getId());
        assertThat(row.getStatus()).isEqualTo(InteractionStatus.ACCEPTED);
        assertThat(row.getRequestText()).isEqualTo("Why is the verifier slow?");
        assertThat(row.getRequestDigest()).hasSize(64);
        assertThat(row.getUsageStatus()).isEqualTo("UNKNOWN");
        assertThat(row.getExpiresAt())
                .isCloseTo(admission.responseDeadline(), within(10,
                        java.time.temporal.ChronoUnit.MILLIS));

        // §3.2: the pending pointer marks the ONE in-flight chat.
        assertThat(currentExecution().getPendingInteractionId())
                .isEqualTo(admission.interactionId());
        assertThat(currentExecution().getNextInteractionOrdinal()).isEqualTo(2L);
    }

    @Test
    @DisplayName("admit: serial ordinals allocate under the execution row lock (1, 2, 3)")
    void admitAllocatesSerialOrdinals() {
        UUID first = service.admit(scope(), editor(), UUID.randomUUID(), "one").interactionId();
        settleOutcome(first, completePayload(first, 1L, "answer one"));
        UUID second = service.admit(scope(), editor(), UUID.randomUUID(), "two").interactionId();
        settleOutcome(second, completePayload(second, 2L, "answer two"));
        UUID third = service.admit(scope(), editor(), UUID.randomUUID(), "three").interactionId();

        assertThat(interactions.findByExecutionIdOrderByOrdinalAsc(execution.getId()))
                .extracting(ExecutionInteraction::getOrdinal)
                .containsExactly(1L, 2L, 3L);
        assertThat(currentExecution().getPendingInteractionId()).isEqualTo(third);
    }

    @Test
    @DisplayName("admit: one pending interaction per execution (409 RESOURCE_IN_USE on the concurrent POST)")
    void concurrentAdmissionAdmitsExactlyOne() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Runnable contender = () -> {
            try {
                ready.countDown();
                release.await();
                TransactionTemplate tx = txTemplate();
                tx.executeWithoutResult(s -> service.admit(scope(), editor(),
                        UUID.randomUUID(), "race"));
                admitted.incrementAndGet();
            } catch (Throwable e) {
                failure.set(e);
            }
        };
        try {
            pool.submit(contender);
            pool.submit(contender);
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(admitted.get()).as("exactly one contender admits").isEqualTo(1);
        List<ExecutionInteraction> rows = interactions
                .findByExecutionIdOrderByOrdinalAsc(execution.getId());
        assertThat(rows).hasSize(1);
        ExecutionInteractionService.ServiceError refused =
                service.admitRefusal(scope(), editor(), UUID.randomUUID(), "x");
        assertThat(refused.errorCode()).isEqualTo("RESOURCE_IN_USE");
    }

    @Test
    @DisplayName("admit: identical retry (same clientRequestId + same bytes) replays the SAME record")
    void identicalRetryReplaysSameRecord() {
        UUID clientRequestId = UUID.randomUUID();
        InteractionAdmission first = service.admit(scope(), editor(), clientRequestId, "hello");
        InteractionAdmission replay = service.admit(scope(), editor(), clientRequestId, "hello");

        assertThat(replay.interactionId()).isEqualTo(first.interactionId());
        assertThat(replay.ordinal()).isEqualTo(first.ordinal());
        assertThat(replay.status()).isEqualTo(first.status());
        assertThat(interactions.findByExecutionIdOrderByOrdinalAsc(execution.getId()))
                .hasSize(1);
        // No second outbox row for the replay.
        assertThat(outboxRepository.findByExecutionIdOrderBySequenceAsc(execution.getId()))
                .hasSize(1);
    }

    @Test
    @DisplayName("admit: identical retry AFTER an outcome settles the SAME record with its outcome (no re-admission)")
    void identicalRetryAfterOutcomeReplaysSettledRecord() {
        UUID clientRequestId = UUID.randomUUID();
        InteractionAdmission first = service.admit(scope(), editor(), clientRequestId, "hello");
        settleOutcome(first.interactionId(), completePayload(first.interactionId(), 1L, "done"));

        InteractionAdmission replay = service.admit(scope(), editor(), clientRequestId, "hello");
        assertThat(replay.interactionId()).isEqualTo(first.interactionId());
        assertThat(replay.ordinal()).isEqualTo(1L);
        ExecutionInteraction row = interactions.findById(first.interactionId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(InteractionStatus.COMPLETED);
        assertThat(row.getAnswerText()).isEqualTo("done");
        // A settled interaction left no pending pointer: the replay must not re-admit.
        assertThat(currentExecution().getPendingInteractionId()).isNull();
        assertThat(outboxRepository.findByExecutionIdOrderBySequenceAsc(execution.getId()))
                .hasSize(1);
    }

    @Test
    @DisplayName("admit: same clientRequestId with DIFFERENT bytes → 409 DUPLICATE_CODE")
    void sameClientRequestIdDifferentBytesFails() {
        UUID sameClientRequestId = UUID.randomUUID();
        service.admit(scope(), editor(), sameClientRequestId, "first bytes");
        assertThatThrownBy(() -> service.admit(scope(), editor(), sameClientRequestId, "second bytes"))
                .isInstanceOf(DuplicateResourceException.class);
        assertThat(interactions.findByExecutionIdOrderByOrdinalAsc(execution.getId()))
                .hasSize(1);
    }

    @Test
    @DisplayName("admit: VIEWER-equivalent denied (403); EDITOR-equivalent allowed")
    void viewerDenied() {
        var viewer = principal(viewerUser.getId(),
                List.of("proj:" + project.getId() + ":VIEWER"));
        assertThatThrownBy(() -> service.admit(scope(), viewer, UUID.randomUUID(), "read-only chat"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(interactions.findByExecutionIdOrderByOrdinalAsc(execution.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("admit: empty/blank text → 400 VALIDATION_ERROR; text over maxInputBytes → 400")
    void textValidation() {
        assertThatThrownBy(() -> service.admit(scope(), editor(), UUID.randomUUID(), ""))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.admit(scope(), editor(), UUID.randomUUID(), "   "))
                .isInstanceOf(BadRequestException.class);
        String oversized = "y".repeat(InteractionProperties.DEFAULT_MAX_INPUT_BYTES + 1);
        assertThatThrownBy(() -> service.admit(scope(), editor(), UUID.randomUUID(), oversized))
                .isInstanceOf(BadRequestException.class);
        assertThat(interactions.findByExecutionIdOrderByOrdinalAsc(execution.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("admit on a terminal execution → 409 reasonCode EXECUTION_TERMINAL")
    void admitOnTerminalRejected() {
        // No admission has happened yet — the seed entity IS current here.
        execution.setState(SessionExecution.State.COMPLETED);
        execution.setTerminalMessageId("tm-done");
        executions.saveAndFlush(execution);

        assertThatThrownBy(() -> service.admit(scope(), editor(), UUID.randomUUID(), "hello"))
                .isInstanceOf(ResourceInUseException.class)
                .hasMessageContaining("EXECUTION_TERMINAL");
        assertThat(interactions.findByExecutionIdOrderByOrdinalAsc(execution.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("admit: foreign attempt (ownership-chain mismatch) → 404 before any host lookup")
    void foreignAttemptFails404() {
        ExecutionScope bad = new ExecutionScope(project.getId(), workflow.getId(),
                request.getId(), task.getId(), UUID.randomUUID(), execution.getId());
        assertThatThrownBy(() -> service.admit(bad, editor(), UUID.randomUUID(), "hi"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(interactions.findByExecutionIdOrderByOrdinalAsc(execution.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("admit: dispatches the execution.interaction command through the SAME outbox (persist → post-commit)")
    void admissionInsertsOutboxEnvelope() {
        InteractionAdmission admission = service.admit(scope(), editor(),
                UUID.randomUUID(), "send me to the host");

        List<ExecutionCommandOutbox> outbox = outboxRepository
                .findByExecutionIdOrderBySequenceAsc(execution.getId());
        assertThat(outbox).hasSize(1);
        ExecutionCommandOutbox entry = outbox.get(0);
        assertThat(entry.getType()).isEqualTo("execution.interaction");
        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(entry.getPayloadDigest()).hasSize(64);
        assertThat(entry.getSessionId()).isEqualTo(session.getId());
        assertThat(entry.getHostInstanceId()).isEqualTo(session.getHostInstanceId());
        Map<String, Object> payload = payloadOf(entry);
        assertThat(String.valueOf(payload.get("interactionId")))
                .isEqualTo(admission.interactionId().toString());
        assertThat(payload.get("ordinal")).isEqualTo(1);
        assertThat(payload.get("dispatchId")).isEqualTo(attempt.getId().toString());
        // §22.6 engine-stamped actor.
        assertThat(String.valueOf(payload.get("actorUserId")))
                .isEqualTo(editorUser.getId().toString());
        assertThat(payload.get("acceptedAt")).isNotNull();

        // The dispatch seam re-serializes the STORED envelope (§3.4).
        var captured = new java.util.ArrayList<String>();
        dispatcher.sendNow(entry.getId(), json -> {
            captured.add(json);
            return true;
        });
        assertThat(captured).hasSize(1);
        assertThat(captured.get(0)).contains("execution.interaction");
        assertThat(captured.get(0)).contains(admission.interactionId().toString());
    }

    @Test
    @DisplayName("admit: responseDeadline = min(acceptedAt + responseTimeoutSeconds, execution deadline)")
    void responseDeadlineIsMinOfTimeoutAndDeadline() {
        // Deadline 600s away; timeout 120s → the admission timeout binds.
        InteractionAdmission normal = service.admit(scope(), editor(),
                UUID.randomUUID(), "normal");
        assertThat(normal.responseDeadline())
                .isAfter(Instant.now().plusSeconds(110))
                .isBefore(Instant.now().plusSeconds(130));

        // Settle the pending interaction so a new admission is possible
        // (ONE pending slot per execution — §22.5).
        settleOutcome(normal.interactionId(), completePayload(normal.interactionId(), 1L,
                "settled"));

        // The execution deadline inside the timeout window → the deadline binds.
        tx(() -> {
            SessionExecution locked = executions.findWithLockById(execution.getId())
                    .orElseThrow();
            locked.setDeadline(Instant.now().plusSeconds(10)
                    .truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
            executions.save(locked);
        });
        InteractionAdmission tight = service.admit(scope(), editor(),
                UUID.randomUUID(), "tight");
        assertThat(tight.responseDeadline())
                .isBefore(Instant.now().plusSeconds(11));
    }

    // ------------------------------------------------------------------
    // Outcome arms (§22.6 complete/failed) via the service (the boundary
    // handler's duties are §22.3: identity + capability verified there —
    // covered in this class through the direct service contract).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("complete: updates the row COMPLETED + answerText + usage; clears the pending pointer")
    void completeUpdatesRowAndClearsPointer() {
        UUID interactionId = service.admit(scope(), editor(), UUID.randomUUID(),
                "what happened?").interactionId();

        service.complete(scope(), "msg-complete-1", completePayload(interactionId, 1L,
                "The verifier is checking the current candidate."));

        ExecutionInteraction row = interactions.findById(interactionId).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(InteractionStatus.COMPLETED);
        assertThat(row.getAnswerText())
                .isEqualTo("The verifier is checking the current candidate.");
        assertThat(row.getTerminalMessageId()).isEqualTo("msg-complete-1");
        assertThat(row.getCompletedAt()).isNotNull();
        assertThat(row.getUsageStatus()).isEqualTo("KNOWN");
        assertThat(row.getUsage().get("totalTokens")).isEqualTo(150);
        // The usage subtotal is attributed (§3.5).
        assertThat(row.getUsage().get("inputTokens")).isEqualTo(120);
        assertThat(currentExecution().getPendingInteractionId()).isNull();
    }

    @Test
    @DisplayName("complete: allocates the public stream event (§3.5 IDs/state/usage, NOT text copies)")
    void completeAllocatesStreamEvent() {
        UUID interactionId = service.admit(scope(), editor(), UUID.randomUUID(), "hi").interactionId();
        long before = currentExecution().getStreamSequence();

        service.complete(scope(), "msg-stream-1", completePayload(interactionId, 1L, "answer"));

        List<ExecutionEvent> events = executionEventRepository
                .findByExecutionIdOrderByStreamSequenceAsc(execution.getId());
        assertThat(events).isNotEmpty();
        ExecutionEvent event = events.get(events.size() - 1);
        assertThat(event.getStreamSequence()).isEqualTo(before + 1);
        assertThat(currentExecution().getStreamSequence()).isEqualTo(before + 1);
        assertThat(event.getData().get("kind")).isEqualTo("interaction");
        assertThat(event.getData().get("interactionId")).isEqualTo(interactionId.toString());
        assertThat(event.getData().get("outcome")).isEqualTo("COMPLETED");
        // NO transcript text copies ride the event (§3.5).
        assertThat(String.valueOf(event.getData())).doesNotContain("answer");
        assertThat(String.valueOf(event.getData())).doesNotContain("hi");
    }

    @Test
    @DisplayName("complete: duplicate messageId + identical bytes is an idempotent replay (no second event, no second usage)")
    void completeReplayIsIdempotent() {
        UUID interactionId = service.admit(scope(), editor(), UUID.randomUUID(), "hi").interactionId();
        service.complete(scope(), "msg-replay-1", completePayload(interactionId, 1L, "answer"));
        long streamAfterFirst = currentExecution().getStreamSequence();

        service.complete(scope(), "msg-replay-1", completePayload(interactionId, 1L, "answer"));

        ExecutionInteraction row = interactions.findById(interactionId).orElseThrow();
        assertThat(row.getAnswerText()).isEqualTo("answer");
        assertThat(currentExecution().getStreamSequence()).isEqualTo(streamAfterFirst);
    }

    @Test
    @DisplayName("complete: same messageId with CONFLICTING bytes → INVALID_MESSAGE protocol error")
    void completeConflictingBytesFails() {
        UUID interactionId = service.admit(scope(), editor(), UUID.randomUUID(), "hi").interactionId();
        service.complete(scope(), "msg-conflict-1", completePayload(interactionId, 1L, "answer one"));

        assertThatThrownBy(() -> service.complete(scope(), "msg-conflict-1",
                completePayload(interactionId, 1L, "answer two")))
                .isInstanceOf(InteractionProtocolException.class)
                .hasMessageContaining("INVALID_MESSAGE");
        ExecutionInteraction row = interactions.findById(interactionId).orElseThrow();
        assertThat(row.getAnswerText()).isEqualTo("answer one");
    }

    @Test
    @DisplayName("complete: wrong dispatchId → IDENTITY_MISMATCH; nothing persisted")
    void completeWithForeignDispatchRejected() {
        UUID interactionId = service.admit(scope(), editor(), UUID.randomUUID(), "hi").interactionId();
        var payload = new ExecutionInteractionCompletePayload(execution.getId(),
                UUID.randomUUID(), interactionId, 1L,
                new ExecutionInteractionCompletePayload.Answer("hi"),
                new ExecutionInteractionCompletePayload.Usage(1, 1, "m"),
                ExecutionInteractionCompletePayload.UsageStatus.KNOWN,
                List.of(), Instant.now());

        assertThatThrownBy(() -> service.complete(scope(), "msg-foreign", payload))
                .isInstanceOf(InteractionProtocolException.class)
                .hasMessageContaining("IDENTITY_MISMATCH");
        ExecutionInteraction row = interactions.findById(interactionId).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(InteractionStatus.ACCEPTED);
        assertThat(currentExecution().getPendingInteractionId()).isEqualTo(interactionId);
    }

    @Test
    @DisplayName("complete: outcome for an interaction that is NOT the pending pointer → INVALID interaction refusal")
    void completeForNonPendingInteractionRejected() {
        // An ADMITTED chat whose pending pointer was cleared (settled elsewhere).
        UUID interactionId = service.admit(scope(), editor(), UUID.randomUUID(), "hi").interactionId();
        tx(() -> {
            SessionExecution locked = executions.findWithLockById(execution.getId()).orElseThrow();
            locked.setPendingInteractionId(null);
            executions.save(locked);
        });

        assertThatThrownBy(() -> service.complete(scope(), "msg-stale",
                completePayload(interactionId, 1L, "stale answer")))
                .isInstanceOf(InteractionProtocolException.class)
                .hasMessageContaining("INVALID");
        ExecutionInteraction row = interactions.findById(interactionId).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(InteractionStatus.ACCEPTED);
    }

    @Test
    @DisplayName("complete: outcome for an unknown interactionId → IDENTITY_MISMATCH")
    void completeForUnknownInteractionRejected() {
        assertThatThrownBy(() -> service.complete(scope(), "msg-unknown",
                completePayload(UUID.randomUUID(), 1L, "hi")))
                .isInstanceOf(InteractionProtocolException.class)
                .hasMessageContaining("IDENTITY_MISMATCH");
    }

    @Test
    @DisplayName("complete: outcome AFTER execution terminal persists TRANSCRIPT ONLY (no state reopen, no controls)")
    void lateOutcomeAfterTerminalPersistsTranscriptOnly() {
        UUID interactionId = service.admit(scope(), editor(), UUID.randomUUID(), "hi").interactionId();
        // Stamp terminal on the LIVE row (a stale seed entity would regress
        // the stream cursor under the outcome's §3.5 allocation).
        terminalize("tm-term");
        // The admission outbox row is the ONLY dispatch record admitted.
        int outboxBefore = outboxRepository
                .findByExecutionIdOrderBySequenceAsc(execution.getId()).size();

        service.complete(scope(), "msg-late-1", completePayload(interactionId, 1L, "late answer"));

        ExecutionInteraction row = interactions.findById(interactionId).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(InteractionStatus.COMPLETED);
        assertThat(row.getAnswerText()).isEqualTo("late answer");
        // The execution stays terminal — no reopen, no control emission.
        SessionExecution after = currentExecution();
        assertThat(after.getState()).isEqualTo(SessionExecution.State.COMPLETED);
        assertThat(after.getPendingInteractionId()).isNull();
        // The engine issued NO control command for the late outcome.
        assertThat(outboxRepository.findByExecutionIdOrderBySequenceAsc(execution.getId()))
                .hasSize(outboxBefore);
    }

    @Test
    @DisplayName("fail: sets FAILED + error jsonb + partial usage; clears the pointer")
    void failSetsFailedRow() {
        UUID interactionId = service.admit(scope(), editor(), UUID.randomUUID(), "hi").interactionId();

        service.fail(scope(), "msg-fail-1", failedPayload(interactionId, 1L,
                ExecutionInteractionFailedPayload.ErrorCode.INTERACTION_TIMEOUT, "deadline",
                new ExecutionInteractionFailedPayload.Usage(40, 5, "gpt")));

        ExecutionInteraction row = interactions.findById(interactionId).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(InteractionStatus.FAILED);
        assertThat(row.getAnswerText()).isNull();
        assertThat(row.getError().get("errorCode")).isEqualTo("INTERACTION_TIMEOUT");
        assertThat(row.getUsageStatus()).isEqualTo("KNOWN");
        assertThat(row.getUsage().get("totalTokens")).isEqualTo(45);
        assertThat(currentExecution().getPendingInteractionId()).isNull();
    }

    @Test
    @DisplayName("fail: UNKNOWN usage keeps status UNKNOWN (never zero)")
    void failWithUnknownUsageKeepsUnknown() {
        UUID interactionId = service.admit(scope(), editor(), UUID.randomUUID(), "hi").interactionId();
        service.fail(scope(), "msg-fail-u", failedPayload(interactionId, 1L,
                ExecutionInteractionFailedPayload.ErrorCode.MODEL_ERROR, "crash", null));
        assertThat(interactions.findById(interactionId).orElseThrow().getUsageStatus())
                .isEqualTo("UNKNOWN");
    }

    // ------------------------------------------------------------------
    // Proposals tie into the pending-pointer contract (Task 6)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("late proposals cannot create controls: proposal for a settled/failed interaction refused")
    void lateProposalsCannotCreateControls() {
        UUID interactionId = service.admit(scope(), editor(), UUID.randomUUID(), "hi").interactionId();
        settleOutcome(interactionId, completePayload(interactionId, 1L, "done"));

        ProposalReceipt receipt = controlService.propose(scope(),
                new ai.myrmec.engine.websocket.host.payload.ExecutionControlRequestPayload(
                        execution.getId(), attempt.getId(), interactionId, UUID.randomUUID(),
                        ai.myrmec.engine.websocket.host.payload.ExecutionControlRequestPayload
                                .Action.HOLD,
                        "hold after settle"));
        assertThat(receipt.status()).isEqualTo("REJECTED");
        assertThat(receipt.errorCode()).isEqualTo("INVALID_INTERACTION");
        // No NEW outbox rows: the ONLY entry remains the admission's own
        // execution.interaction command (a refused proposal creates none).
        List<ExecutionCommandOutbox> outbox = outboxRepository
                .findByExecutionIdOrderBySequenceAsc(execution.getId());
        assertThat(outbox).hasSize(1);
        assertThat(outbox.get(0).getType()).isEqualTo("execution.interaction");
    }

    // ------------------------------------------------------------------
    // Retention sweeper (§3.5 + §22.8)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("sweeper: accepted interaction past responseDeadline on a TERMINAL execution → FAILED EXECUTION_TERMINAL with explicit usage status")
    void sweepUnsettledPastDeadlineOnTerminalExecution() {
        UUID interactionId = service.admit(scope(), editor(), UUID.randomUUID(), "hi").interactionId();
        // Stamp terminal on the LIVE row — saving the stale seed entity here
        // would regress the stream cursor (and collide the unique
        // (execution, stream_sequence) index) under the sweeps (Fix 1).
        terminalize("tm-1");
        ageRow(interactionId, -1);

        int swept = sweeper.sweepOnce(Instant.now());

        assertThat(swept).isEqualTo(1);
        ExecutionInteraction row = interactions.findById(interactionId).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(InteractionStatus.FAILED);
        assertThat(row.getError().get("errorCode")).isEqualTo("EXECUTION_TERMINAL");
        assertThat(row.getUsageStatus()).isEqualTo("UNKNOWN");
        assertThat(currentExecution().getPendingInteractionId()).isNull();
        // The transcript is intact (retention has not elapsed).
        assertThat(row.getRequestText()).isEqualTo("hi");
    }

    @Test
    @DisplayName("sweeper: unsettled interactions past their ORIGINAL responseDeadline (not an extended post-terminal timeout)")
    void sweepUsesOriginalResponseDeadline() {
        UUID interactionId = service.admit(scope(), editor(), UUID.randomUUID(), "hi").interactionId();
        Instant originalDeadline = interactions.findById(interactionId).orElseThrow()
                .getResponseDeadline();
        // Stamp terminal on the LIVE row (stale stale-seed saves regress the
        // stream cursor — see Fix 1).
        tx(() -> {
            SessionExecution live = executions.findWithLockById(execution.getId())
                    .orElseThrow();
            live.setState(SessionExecution.State.FAILED);
            live.setTerminalMessageId("tm-2");
            executions.save(live);
        });

        // NOT yet past the original deadline → the sweep leaves it alone
        // (even though the execution went terminal).
        int sweptEarly = sweeper.sweepOnce(Instant.now());
        assertThat(sweptEarly).isZero();
        assertThat(interactions.findById(interactionId).orElseThrow().getStatus())
                .isEqualTo(InteractionStatus.ACCEPTED);

        // One second past the original deadline → swept.
        sweeper.sweepOnce(originalDeadline.plusSeconds(1));
        assertThat(interactions.findById(interactionId).orElseThrow().getStatus())
                .isEqualTo(InteractionStatus.FAILED);
    }

    @Test
    @DisplayName("sweeper: settled (COMPLETED/FAILED) interactions are past-deadline sweep-PROOF — only unsettled rows sweep")
    void sweepIgnoresSettledRows() {
        UUID settledId = service.admit(scope(), editor(), UUID.randomUUID(), "settled")
                .interactionId();
        settleOutcome(settledId, completePayload(settledId, 1L, "done"));
        ageRow(settledId, -1);

        int swept = sweeper.sweepOnce(Instant.now());
        assertThat(swept).isZero();
        assertThat(interactions.findById(settledId).orElseThrow().getAnswerText()).isEqualTo("done");
    }

    @Test
    @DisplayName("sweeper: terminal interactions past transcriptRetentionDays redact text to CONTENT_EXPIRED, keep identity/usage")
    void retentionRedactsTranscript() {
        UUID interactionId = service.admit(scope(), editor(), UUID.randomUUID(), "secret question")
                .interactionId();
        settleOutcome(interactionId, completePayload(interactionId, 1L, "secret answer"));
        // Retention (30 days) elapsed.
        tx(() -> {
            ExecutionInteraction row = interactions.findWithLockById(interactionId).orElseThrow();
            row.setCompletedAt(Instant.now().minus(java.time.Duration.ofDays(31)));
            row.setExpiresAt(Instant.now().minus(java.time.Duration.ofDays(31)));
            interactions.save(row);
        });

        int redacted = sweeper.redactExpired(Instant.now());
        assertThat(redacted).isEqualTo(1);
        ExecutionInteraction row = interactions.findById(interactionId).orElseThrow();
        assertThat(row.getRequestText()).contains("CONTENT_EXPIRED");
        assertThat(row.getRequestText()).doesNotContain("secret question");
        assertThat(row.getAnswerText()).contains("CONTENT_EXPIRED");
        assertThat(row.getAnswerText()).doesNotContain("secret answer");
        // Identity/usage/audit metadata retained.
        assertThat(row.getId()).isNotNull();
        assertThat(row.getOrdinal()).isEqualTo(1L);
        assertThat(row.getUsageStatus()).isEqualTo("KNOWN");
        assertThat(row.getUsage()).isNotNull();
    }

    @Test
    @DisplayName("retention: content WITHIN the retention window is untouched")
    void retentionKeepsRecentContent() {
        UUID interactionId = service.admit(scope(), editor(), UUID.randomUUID(), "fresh")
                .interactionId();
        settleOutcome(interactionId, completePayload(interactionId, 1L, "fresh answer"));

        int redacted = sweeper.redactExpired(Instant.now());
        assertThat(redacted).isZero();
        ExecutionInteraction row = interactions.findById(interactionId).orElseThrow();
        assertThat(row.getRequestText()).isEqualTo("fresh");
        assertThat(row.getAnswerText()).isEqualTo("fresh answer");
    }

    @Test
    @DisplayName("late settlement after terminal/redaction updates ACCOUNTING only — never the failed/final answer")
    void lateSettlementUpdatesAccountingOnly() {
        UUID interactionId = service.admit(scope(), editor(), UUID.randomUUID(), "hi").interactionId();
        // The interaction SWEPT to a failure (never got an outcome). The
        // LIVE row is reloaded first — saving the stale seed entity would
        // regress the stream cursor (streamSequence=0) under the sweep.
        terminalize("tm-3");
        ageRow(interactionId, -1);
        sweeper.sweepOnce(Instant.now());
        assertThat(interactions.findById(interactionId).orElseThrow().getStatus())
                .isEqualTo(InteractionStatus.FAILED);
        assertThat(interactions.findById(interactionId).orElseThrow().getAnswerText()).isNull();

        // A late provider settlement with usage: accounting advances…
        usageService.settleUsage(execution.getId(), "settle-late-1",
                InteractionUsageService.Source.INTERACTION, interactionId,
                usage(120, 30), "KNOWN");
        Map<String, Object> accounting = currentExecution().getInteractionUsage();
        assertThat(((Number) accounting.get("accountedTokens")).longValue()).isEqualTo(150L);

        // …but the failed row's answer is NEVER reconstructed.
        assertThat(interactions.findById(interactionId).orElseThrow().getAnswerText()).isNull();
        // The sweep allocated its public §3.5 stream event (IDs/status, not text).
        List<ExecutionEvent> events = executionEventRepository
                .findByExecutionIdOrderByStreamSequenceAsc(execution.getId());
        ExecutionEvent sweptEvent = events.get(events.size() - 1);
        assertThat(sweptEvent.getMessage()).isEqualTo("execution.interaction.failed");
        assertThat(sweptEvent.getData().get("kind")).isEqualTo("interaction");
        assertThat(sweptEvent.getData().get("interactionId")).isEqualTo(interactionId.toString());
        assertThat(sweptEvent.getData().get("outcome")).isEqualTo("FAILED");
        assertThat(sweptEvent.getData().get("errorCode")).isEqualTo("EXECUTION_TERMINAL");
        assertThat(String.valueOf(sweptEvent.getData())).doesNotContain("hi");
    }

    @Test
    @DisplayName("settled row is FROZEN: a differently-keyed late complete keeps FAILED + original error, usage accounting advances (§22.8)")
    void settledRowOutcomeNeverOverwritten() {
        UUID interactionId = service.admit(scope(), editor(), UUID.randomUUID(), "hi")
                .interactionId();
        // Sweep the row to FAILED / EXECUTION_TERMINAL (the sweeper's
        // own settlement, terminalMessageId = "sweep-<id>"). Reload first —
        // saving the stale seed entity would regress the stream cursor.
        terminalize("tm-freeze");
        ageRow(interactionId, -1);
        sweeper.sweepOnce(Instant.now());
        ExecutionInteraction swept = interactions.findById(interactionId).orElseThrow();
        assertThat(swept.getStatus()).isEqualTo(InteractionStatus.FAILED);
        assertThat(swept.getError().get("errorCode")).isEqualTo("EXECUTION_TERMINAL");
        String sweptTerminalMessageId = swept.getTerminalMessageId();

        // A LATE complete with a NEW messageId: usage accounting may settle…
        service.complete(scope(), "msg-late-new-key",
                completePayload(interactionId, 1L, "the late answer text"));

        ExecutionInteraction frozen = interactions.findById(interactionId).orElseThrow();
        // …but the outcome stays FROZEN: still FAILED, the ORIGINAL error,
        // no answer, terminalMessageId/completedAt untouched.
        assertThat(frozen.getStatus()).isEqualTo(InteractionStatus.FAILED);
        assertThat(frozen.getError().get("errorCode")).isEqualTo("EXECUTION_TERMINAL");
        assertThat(frozen.getAnswerText()).isNull();
        assertThat(frozen.getTerminalMessageId()).isEqualTo(sweptTerminalMessageId);
        // Usage accounting advanced through the late settlement seam.
        Map<String, Object> accounting = currentExecution().getInteractionUsage();
        assertThat(((Number) accounting.get("accountedTokens")).longValue()).isEqualTo(150L);
    }

    @Test
    @DisplayName("settled row is FROZEN: the late frame replays (same messageId) → accounting charged once")
    void settledRowLateReplaySettlesUsageOnce() {
        UUID interactionId = service.admit(scope(), editor(), UUID.randomUUID(), "hi")
                .interactionId();
        settleOutcome(interactionId, completePayload(interactionId, 1L, "done"));
        assertThat(currentExecution().getInteractionUsage() == null ? 0L
                : ((Number) currentExecution().getInteractionUsage()
                .get("accountedTokens")).longValue()).isEqualTo(150L);

        // A differently-keyed second outcome reports a BIGGER cumulative —
        // the freeze stops the answer flip, the accounting settles the delta.
        service.complete(scope(), "msg-late-bigger",
                new ExecutionInteractionCompletePayload(execution.getId(), attempt.getId(),
                        interactionId, 1L,
                        new ExecutionInteractionCompletePayload.Answer("MUTATED ANSWER"),
                        new ExecutionInteractionCompletePayload.Usage(300, 50, "orch-model"),
                        ExecutionInteractionCompletePayload.UsageStatus.KNOWN,
                        List.of(), Instant.now()));

        ExecutionInteraction frozen = interactions.findById(interactionId).orElseThrow();
        assertThat(frozen.getAnswerText()).isEqualTo("done");
        assertThat(frozen.getStatus()).isEqualTo(InteractionStatus.COMPLETED);
        assertThat(frozen.getTerminalMessageId())
                .isEqualTo("msg-seed-" + interactionId);
        // Cumulative 350 - accounted 150 = +200 charged (the honest delta).
        Map<String, Object> accounting = currentExecution().getInteractionUsage();
        assertThat(((Number) accounting.get("accountedTokens")).longValue()).isEqualTo(350L);
    }

    // ------------------------------------------------------------------
    // Engine-side outbound secret scanning (§22.6: the engine's own layer)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("admission BLOCK mode: secret-shaped requestText → CAPTURE_BLOCKED fail-closed")
    void admissionBlocksSecretText() {
        String secret = "myr_agent_verysecrettokenvalue";
        assertThatThrownBy(() -> service
                .admit(scope(), editor(), UUID.randomUUID(),
                        "here is a key " + secret + " keep it"))
            .as("fail-closed when the outbound scan blocks")
            .isInstanceOf(InteractionCaptureBlockedException.class)
            .hasMessageContaining("CAPTURE_BLOCKED");
        assertThat(interactions.findByExecutionIdOrderByOrdinalAsc(execution.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("complete: secret-shaped answerText settles USAGE but the answer is redacted/marked (§22.6)")
    void completeRedactsSecretAnswer() {
        UUID interactionId = service.admit(scope(), editor(), UUID.randomUUID(), "hi")
                .interactionId();
        service.complete(scope(), "msg-secret-1", completePayload(interactionId, 1L,
                "the key is myr_agent_aaaaabbbbbccccc keep it secret"));

        ExecutionInteraction row = interactions.findById(interactionId).orElseThrow();
        assertThat(row.getUsageStatus()).isEqualTo("KNOWN");
        assertThat(row.getUsage()).isNotNull();
        // §22.6: later provider evidence settles usage but cannot replace
        // the answer unchecked — the engine's redaction layer marked it.
        assertThat(String.valueOf(row.getAnswerText()))
                .doesNotContain("myr_agent_aaaaabbbbbccccc");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void settleOutcome(UUID interactionId,
                               ExecutionInteractionCompletePayload payload) {
        service.complete(scope(), "msg-seed-" + interactionId, payload);
    }

    private ExecutionInteractionCompletePayload completePayload(UUID interactionId, long ordinal,
                                                               String answer) {
        return new ExecutionInteractionCompletePayload(execution.getId(), attempt.getId(),
                interactionId, ordinal,
                new ExecutionInteractionCompletePayload.Answer(answer),
                new ExecutionInteractionCompletePayload.Usage(120, 30, "orch-model"),
                ExecutionInteractionCompletePayload.UsageStatus.KNOWN,
                List.of(), Instant.now());
    }

    private ExecutionInteractionFailedPayload failedPayload(UUID interactionId, long ordinal,
                                                            ExecutionInteractionFailedPayload.ErrorCode code,
                                                            String message,
                                                            ExecutionInteractionFailedPayload.Usage usage) {
        return new ExecutionInteractionFailedPayload(execution.getId(), attempt.getId(),
                interactionId, ordinal,
                new ExecutionInteractionFailedPayload.Error(code, message, false),
                usage, usage == null
                    ? ExecutionInteractionFailedPayload.UsageStatus.UNKNOWN
                    : ExecutionInteractionFailedPayload.UsageStatus.KNOWN,
                List.of(), Instant.now());
    }

    private Map<String, Object> usage(long input, long output) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("inputTokens", input);
        map.put("outputTokens", output);
        return map;
    }

    private void ageRow(UUID interactionId, long seconds) {
        tx(() -> {
            ExecutionInteraction row = interactions.findWithLockById(interactionId)
                    .orElseThrow();
            row.setResponseDeadline(Instant.now().plusSeconds(seconds)
                    .truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
            interactions.save(row);
        });
    }

    /** Stamp the execution terminal on the LIVE row (a saved stale seed
     * entity would regress the stream cursor under later allocations). */
    private void terminalize(String terminalMessageId) {
        tx(() -> {
            SessionExecution live = executions.findWithLockById(execution.getId())
                    .orElseThrow();
            live.setState(SessionExecution.State.COMPLETED);
            live.setTerminalMessageId(terminalMessageId);
            executions.save(live);
        });
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
        user.setEmail("adm-" + suffix + "-" + System.nanoTime() + "@test.local");
        user.setName("Adm " + suffix);
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
        wf.setName("adm-wf-" + System.nanoTime());
        wf.setSteps(List.of());
        wf.setVersion(1);
        wf.setStatus(WorkflowStatus.PUBLISHED);
        wf.setCreatedBy(userRepository.findById(TEST_ADMIN_ID).orElseThrow());
        return workflowRepository.save(wf);
    }
}
