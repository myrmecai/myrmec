// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.myrmec.engine.websocket.InboundOrchestrationHandler;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Engine-only contract assertions (design §19.3, post-F10 hardening):
 * malformed, mismatched, and forged orchestration frames fail closed —
 * audited and dropped, never mutating engine state — and duplicate
 * terminal results are idempotent (§7.3: one logical result per
 * dispatch; identical resultId+digest replays the stored outcome, a
 * different digest is a conflict).
 *
 * <p>Every frame goes through the REAL inbound handler boundary — the
 * same code path {@code AgentWebSocketHandler.handleTextMessage} routes
 * a connected agent's frames through — so these tests prove the
 * wire-level contract, not the service internals.</p>
 */
@DisplayName("Engine contract: hostile orchestration frames fail closed (§16.3/§7.3)")
class OrchestrationHostileFramesTest extends IntegrationTestBase {

    @Autowired private TestDataBuilder data;
    @Autowired private InboundOrchestrationHandler inboundHandler;
    @Autowired private OrchestrationOutcomeService outcomeService;
    @Autowired private OrchestrationDispatchRelay dispatchRelay;
    @Autowired private OrchestrationEventIngestionService eventIngestionService;
    @Autowired private OrchestrationRunService runService;
    @Autowired private OrchestrationRunRepository runRepository;
    @Autowired private WorkflowTaskRepository taskRepository;
    @Autowired private WorkflowRequestRepository requestRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private TaskAttemptRepository attemptRepository;
    @Autowired private OrchestrationDispatchRepository dispatchRepository;
    @Autowired private ExecutionEventRepository eventRepository;
    @Autowired private ObjectMapper objectMapper;

    /** Fresh reads after handler-driven transactions commit — the test's
     * own persistence context otherwise serves stale pre-update entities. */
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    private static final UUID AGENT_INSTANCE = UUID.randomUUID();

    private WorkflowRequest request;
    private WorkflowTask task;

    @BeforeEach
    void setUp() {
        Project project = data.project().named("hostile").withRepo("https://x.git", "main").create();
        AgentProfile profile = data.agentProfile().named("hostile-profile").create();
        User admin = userRepository.findById(TEST_ADMIN_ID).orElseThrow();

        Workflow wf = new Workflow();
        wf.setProject(project);
        wf.setName("hostile-wf-" + System.nanoTime());
        wf.setSteps(java.util.List.<Map<String, Object>>of());
        wf.setVersion(1);
        wf.setStatus(WorkflowStatus.PUBLISHED);
        wf.setCreatedBy(admin);
        wf = workflowRepository.save(wf);

        request = new WorkflowRequest();
        request.setWorkflow(wf);
        request.setWorkflowVersion(1);
        request.setInput(Map.of());
        request.setStatus(RequestStatus.RUNNING);
        request.setBranch("myrmec/hostile");
        request.setCreatedBy(admin);
        request.setCreatedAt(Instant.now());
        request = requestRepository.save(request);

        // Pin the orchestration run (id == request id, real profile-version
        // FK) — dispatch/event/result fixtures reference it.
        runService.pinRun(request.getId(), wf.getId(), project.getId(), profile.getId());

        task = new WorkflowTask();
        task.setRequest(request);
        task.setStepId("build");
        task.setAgentProfile(profile);
        task.setInput(Map.of());
        task.setStatus(TaskStatus.RUNNING);
        task.setAttempt(1);
        task.setMaxRetries(1);
        task = taskRepository.save(task);
    }

    private TaskAttempt runningAttempt() {
        TaskAttempt attempt = task.createAttempt(null);
        attempt = attemptRepository.save(attempt);
        task = taskRepository.findById(task.getId()).orElseThrow();
        return attempt;
    }

    /** Detach everything so post-handler reads see the COMMITTED rows. */
    private void detachAll() {
        entityManager.clear();
    }

    private JsonNode frame(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    private String resultFrame(UUID dispatchId, UUID resultId, String digest,
                               String status, String summary) throws Exception {
        return """
                {"schemaVersion":"1.0","resultId":"%s","resultDigest":"%s",\
                "dispatch":{"workflowId":"wf-1","runId":"%s","stepId":"build",\
                "taskId":"%s","attemptId":"%s","attemptOrdinal":1,"dispatchId":"%s"},\
                "status":"%s","retryDisposition":"NONE","summary":"%s",\
                "workerCalls":[],"verifierResults":[],"commandExecutions":[],\
                "changedFiles":[],"commits":[],"cleanWorktree":true,\
                "usage":{"workerCalls":0,"rejectionCount":0,"totalTokens":0}}
                """.formatted(resultId, digest, request.getId(), task.getId(),
                dispatchId, dispatchId, status, summary);
    }

    // ── A2: duplicate terminal-result idempotency (§7.3) ──────

    @Test
    @DisplayName("an identical re-delivered result replays the stored outcome — one state change")
    void duplicateResultReplays() throws Exception {
        TaskAttempt attempt = runningAttempt();
        UUID resultId = UUID.randomUUID();

        inboundHandler.onOrchestrationResult(AGENT_INSTANCE,
                frame(resultFrame(attempt.getId(), resultId, "d1", "COMPLETED", "done")));
        Awaitility.await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    detachAll();
                    assertThat(attemptRepository.findById(attempt.getId()).orElseThrow().getStatus())
                            .isEqualTo(AttemptStatus.COMPLETED);
                });

        // The outbox may retransmit — the identical frame must be a
        // no-op (replay), never a second state change.
        inboundHandler.onOrchestrationResult(AGENT_INSTANCE,
                frame(resultFrame(attempt.getId(), resultId, "d1", "COMPLETED", "done")));
        detachAll();
        TaskAttempt stored2 = attemptRepository.findById(attempt.getId()).orElseThrow();
        assertThat(stored2.getStatus()).isEqualTo(AttemptStatus.COMPLETED);
        assertThat(stored2.getOrchestrationResultId()).isEqualTo(resultId);
        // And the task/request landed in exactly one terminal state.
        assertThat(taskRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.COMPLETED);
        assertThat(requestRepository.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(RequestStatus.COMPLETED);
    }

    @Test
    @DisplayName("a DIFFERENT result for a completed dispatch is a conflict — state stays stored")
    void conflictingResultFailsClosed() throws Exception {
        TaskAttempt attempt = runningAttempt();
        UUID firstResultId = UUID.randomUUID();
        UUID forgedResultId = UUID.randomUUID();

        inboundHandler.onOrchestrationResult(AGENT_INSTANCE,
                frame(resultFrame(attempt.getId(), firstResultId, "d1", "COMPLETED", "done")));
        assertThat(attemptRepository.findById(attempt.getId()).orElseThrow()
                .getOrchestrationResultId()).isEqualTo(firstResultId);

        // A second, conflicting result (different resultId) for the same
        // dispatch is rejected — the stored outcome stands.
        inboundHandler.onOrchestrationResult(AGENT_INSTANCE,
                frame(resultFrame(attempt.getId(), forgedResultId, "d2", "FAILED", "forged")));

        detachAll();
        TaskAttempt stored = attemptRepository.findById(attempt.getId()).orElseThrow();
        assertThat(stored.getOrchestrationResultId())
                .as("the forged result must not overwrite the stored outcome")
                .isEqualTo(firstResultId);
        assertThat(stored.getStatus()).isEqualTo(AttemptStatus.COMPLETED);
        assertThat(taskRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.COMPLETED);
    }

    // ── A1: malformed identity → dropped, no state ───────────

    @Test
    @DisplayName("a result missing its identity fields is dropped — no state changes")
    void resultMissingIdentityIsDropped() throws Exception {
        TaskAttempt attempt = runningAttempt();
        // No dispatchId, no resultId, no digest.
        inboundHandler.onOrchestrationResult(AGENT_INSTANCE,
                frame("{\"status\":\"COMPLETED\",\"summary\":\"partial\"}"));

        detachAll();
        assertThat(attemptRepository.findById(attempt.getId()).orElseThrow().getStatus())
                .as("a frame missing identity must not complete the attempt")
                .isEqualTo(AttemptStatus.RUNNING);
        assertThat(attemptRepository.findById(attempt.getId()).orElseThrow()
                .getOrchestrationResultId()).isNull();
    }

    @Test
    @DisplayName("a result for an UNKNOWN dispatch is dropped silently")
    void resultForUnknownDispatchIsDropped() throws Exception {
        TaskAttempt attempt = runningAttempt();
        UUID unknownDispatch = UUID.randomUUID();
        // A result claiming a dispatch that has no engine row: the
        // outcome path throws inside the handler's catch — no leak, no
        // state change on the REAL attempt.
        inboundHandler.onOrchestrationResult(AGENT_INSTANCE,
                frame(resultFrame(unknownDispatch, UUID.randomUUID(), "dx", "COMPLETED", "x")));

        detachAll();
        assertThat(attemptRepository.findById(attempt.getId()).orElseThrow().getStatus())
                .isEqualTo(AttemptStatus.RUNNING);
    }

    @Test
    @DisplayName("a result with an UNKNOWN status string is dropped")
    void resultWithUnknownStatusIsDropped() throws Exception {
        TaskAttempt attempt = runningAttempt();
        inboundHandler.onOrchestrationResult(AGENT_INSTANCE,
                frame(resultFrame(attempt.getId(), UUID.randomUUID(), "d1", "FINISHED", "x")));

        detachAll();
        assertThat(attemptRepository.findById(attempt.getId()).orElseThrow().getStatus())
                .isEqualTo(AttemptStatus.RUNNING);
    }

    @Test
    @DisplayName("an approval request missing its identity is dropped — no approval row")
    void approvalRequestMissingIdentityIsDropped() throws Exception {
        TaskAttempt attempt = runningAttempt();
        // No dispatchId/approvalRequestId correlation.
        inboundHandler.onOrchestrationApprovalRequested(AGENT_INSTANCE,
                frame("{\"schemaVersion\":\"1.0\",\"action\":{\"actionId\":\"a\","
                        + "\"type\":\"WORKER_TOOL\",\"riskClass\":\"DESTRUCTIVE\","
                        + "\"summary\":\"worker:c:IMPLEMENT\",\"digest\":\""
                        + "a".repeat(64) + "\"}}"));

        detachAll();
        WorkflowTask stored = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(stored.getApprovalStatus())
                .as("a malformed approval request must never create a pending approval")
                .isNull();
        // JsonMapConverter materializes a NULL map as EMPTY — key on
        // emptiness, never null-ness.
        assertThat(stored.getApprovalPayload() == null || stored.getApprovalPayload().isEmpty())
                .as("a malformed approval request must never create a payload")
                .isTrue();
        // The attempt is untouched too.
        detachAll();
        assertThat(attemptRepository.findById(attempt.getId()).orElseThrow().getStatus())
                .isEqualTo(AttemptStatus.RUNNING);
    }

    // ── A1: mismatched correlation → fail closed ─────────────

    @Test
    @DisplayName("inference.accept with a CONFLICTING digest fails closed — dispatch stays PENDING")
    void acceptWithConflictingDigestFailsClosed() {
        TaskAttempt attempt = runningAttempt();
        String canonical = "{\"schemaVersion\":\"1.0\"}";
        OrchestrationDispatch dispatch = dispatchRelay.recordDispatch(
                attempt.getId(), request.getId(), task.getId(),
                canonical, "digest-original");

        // The wrong digest (forged or stale bytes) is refused.
        boolean admitted = dispatchRelay.accept(attempt.getId(), "digest-forged");
        assertThat(admitted).isFalse();

        detachAll();
        OrchestrationDispatch stored = dispatchRepository
                .findById(attempt.getId()).orElseThrow();
        assertThat(stored.getDeliveryState())
                .as("a conflicting digest must leave the dispatch PENDING for relay resend")
                .isEqualTo("PENDING");
        assertThat(stored.isAccepted()).isFalse();

        // The CORRECT digest still admits afterwards (the relay recovers).
        assertThat(dispatchRelay.accept(attempt.getId(), "digest-original")).isTrue();
        assertThat(dispatchRepository.findById(attempt.getId()).orElseThrow()
                .isAccepted()).isTrue();

        // Replay of the correct accept returns the stored acknowledgement.
        assertThat(dispatchRelay.accept(attempt.getId(), "digest-original")).isTrue();
    }

    @Test
    @DisplayName("inference.accept for an unknown dispatch is refused — not a new row")
    void acceptForUnknownDispatchIsRefused() {
        TaskAttempt attempt = runningAttempt();
        dispatchRelay.recordDispatch(attempt.getId(), request.getId(), task.getId(),
                "{}", "d");

        // Fails closed: the unknown id throws from the locked lookup —
        // nothing is admitted and nothing is created.
        UUID unknownDispatch = UUID.randomUUID();
        assertThatThrownBy(() -> dispatchRelay.accept(unknownDispatch, "d"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown dispatch");
        detachAll();
        assertThat(dispatchRepository.findById(unknownDispatch)).isEmpty();
        assertThat(dispatchRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("an orchestration event claiming an OCCUPIED sequence slot is rejected")
    void eventSlotCollisionIsRejected() throws Exception {
        TaskAttempt attempt = runningAttempt();
        UUID sourceEventId = UUID.randomUUID();
        String eventJson = """
                {"schemaVersion":"1.0","eventId":"%s",\
                "dispatch":{"workflowId":"wf-1","runId":"%s","stepId":"build",\
                "taskId":"%s","attemptId":"%s","attemptOrdinal":1,"dispatchId":"%s"},\
                "type":"WORKER_STARTED","sequence":1,"occurredAt":"2026-01-01T00:00:00Z",\
                "workerName":"coder"}
                """.formatted(sourceEventId, request.getId(), task.getId(),
                attempt.getId(), attempt.getId());
        inboundHandler.onOrchestrationEvent(AGENT_INSTANCE, frame(eventJson));
        assertThat(eventRepository.findBySourceEventId(sourceEventId)).isPresent();

        // A DIFFERENT event id claiming the same (attempt, sequence=1)
        // slot is a protocol violation — rejected.
        UUID collidingId = UUID.randomUUID();
        String collidingJson = eventJson.replace(sourceEventId.toString(), collidingId.toString());
        inboundHandler.onOrchestrationEvent(AGENT_INSTANCE, frame(collidingJson));
        detachAll();
        assertThat(eventRepository.findBySourceEventId(collidingId))
                .as("a colliding event must never be ingested")
                .isEmpty();

        // A duplicate delivery of the SAME event is a replay (idempotent).
        inboundHandler.onOrchestrationEvent(AGENT_INSTANCE, frame(eventJson));
        assertThat(eventRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("an event for an unknown dispatch is dropped — no row")
    void eventForUnknownDispatchIsDropped() throws Exception {
        UUID unknownDispatch = UUID.randomUUID();
        UUID sourceEventId = UUID.randomUUID();
        String eventJson = """
                {"schemaVersion":"1.0","eventId":"%s",\
                "dispatch":{"workflowId":"wf-1","runId":"%s","stepId":"build",\
                "taskId":"%s","attemptId":"%s","attemptOrdinal":1,"dispatchId":"%s"},\
                "type":"WORKER_STARTED","sequence":1,"occurredAt":"2026-01-01T00:00:00Z",\
                "workerName":"coder"}
                """.formatted(sourceEventId, request.getId(), task.getId(),
                unknownDispatch, unknownDispatch);
        inboundHandler.onOrchestrationEvent(AGENT_INSTANCE, frame(eventJson));
        assertThat(eventRepository.findBySourceEventId(sourceEventId)).isEmpty();
    }

    // ── A1: forged release acknowledgement → deterministic id check ──

    @Test
    @DisplayName("a release ack with a FORGED acknowledgementId is dropped — lease state intact")
    void forgedReleaseAckFailsClosed() throws Exception {
        // The setUp's pinned run — give it an active lease to observe.
        OrchestrationRun run = runRepository.findById(request.getId()).orElseThrow();
        run.setLeaseState("ACTIVE");
        runRepository.save(run);

        UUID releaseId = UUID.randomUUID();
        UUID forgedAck = UUID.randomUUID(); // NOT the derived UUIDv5

        String ackJson = """
                {"releaseId":"%s","acknowledgementId":"%s","runId":"%s",\
                "status":"RELEASED","workspaceGeneration":1,\
                "occurredAt":"2026-01-01T00:00:00Z"}
                """.formatted(releaseId, forgedAck, request.getId());
        inboundHandler.onReleaseAcknowledgement(AGENT_INSTANCE, frame(ackJson));

        detachAll();
        assertThat(runRepository.findById(request.getId()).orElseThrow().getLeaseState())
                .as("a forged ack must not flip the lease state")
                .isEqualTo("ACTIVE");

        // The DERIVED id passes the check and flips the state (§16.5).
        UUID derived = OrchestrationIds.acknowledgementId(releaseId, 1, "RELEASED");
        String validJson = ackJson.replace(forgedAck.toString(), derived.toString());
        inboundHandler.onReleaseAcknowledgement(AGENT_INSTANCE, frame(validJson));
        detachAll();
        assertThat(runRepository.findById(request.getId()).orElseThrow().getLeaseState())
                .isEqualTo("RELEASED");
    }
}