// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Feature 10 (design §16.3/§16.4/§16.5): dispatch relay accept semantics,
 * idempotent event ingestion, durable affinity throttling, and the
 * deterministic release evidence.
 */
@DisplayName("F10: relay, ingestion, affinity, release")
class OrchestrationDispatchRelayTest extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private OrchestrationDispatchRelay relay;

    @Autowired
    private OrchestrationEventIngestionService ingestion;

    @Autowired
    private OrchestrationAffinityResolver affinity;

    @Autowired
    private WorkspaceReleaseService releaseService;

    @Autowired
    private OrchestrationDispatchRepository dispatchRepository;
    @Autowired
    private OrchestrationRunRepository runRepository;
    @Autowired
    private TaskAttemptRepository attemptRepository;
    @Autowired
    private WorkflowTaskRepository taskRepository;
    @Autowired
    private WorkflowRequestRepository requestRepository;
    @Autowired
    private WorkflowRepository workflowRepository;

    // ── relay (§16.3) ─────────────────────────────────────────

    @Test
    @DisplayName("record→accept is idempotent; a conflicting digest fails closed")
    void relayAcceptSemantics() {
        var fixture = attemptFixture("relay");
        // §16.7: the run row owns the dispatch FK — create it for the request.
        WorkflowRequest requestRow = requestRepository.findById(fixture.runId()).orElseThrow();
        WorkflowTask taskRow = taskRepository.findById(fixture.taskId()).orElseThrow();
        OrchestrationRun run = OrchestrationRun.builder()
                .id(fixture.runId())
                .workflowId(requestRow.getWorkflow().getId())
                .projectId(projectIdOf(requestRow))
                .profileVersionId(publishedVersionOf(taskRow.getAgentProfile().getId()))
                .profileVersionDigest("d-relay")
                .build();
        runRepository.save(run);

        UUID dispatchId = fixture.attemptId();
        relay.recordDispatch(dispatchId, fixture.runId(), fixture.taskId(),
                "{\"a\":1}", "digest-1");

        // Fresh accept with the matching digest.
        assertThat(relay.accept(dispatchId, "digest-1")).isTrue();

        // Replay accept: same digest → the stored acknowledgement.
        assertThat(relay.accept(dispatchId, "digest-1")).isTrue();

        // Conflicting bytes fail closed.
        assertThat(relay.accept(dispatchId, "digest-2")).isFalse();

        var stored = dispatchRepository.findById(dispatchId).orElseThrow();
        assertThat(stored.isAccepted()).isTrue();
        assertThat(stored.getAcceptedAt()).isNotNull();
    }

    // ── event ingestion (§16.3/§21) ────────────────────────────

    @Test
    @DisplayName("events ingest idempotently by source id; conflicting duplicates and slot collisions are rejected")
    void eventIngestionIdempotency() {
        var fixture = attemptFixture("ingest");
        UUID dispatchId = fixture.attemptId();
        UUID eventId = UUID.randomUUID();
        Map<String, Object> envelope = Map.of("workerName", "coder", "totalTokens", 8200);

        assertThat(ingestion.ingest(dispatchId, eventId, 1, "WORKER_STARTED", envelope))
                .isEqualTo(OrchestrationEventIngestionService.IngestResult.INSERTED);
        // Replay: same id, same payload → no-op
        assertThat(ingestion.ingest(dispatchId, eventId, 1, "WORKER_STARTED", envelope))
                .isEqualTo(OrchestrationEventIngestionService.IngestResult.REPLAY);
        // Conflicting duplicate: same id, different payload → reject
        assertThatThrownBy(() -> ingestion.ingest(dispatchId, eventId, 2,
                "WORKER_COMPLETED", envelope))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Conflicting duplicate");
        // Slot collision: a different event claiming sequence 1
        assertThatThrownBy(() -> ingestion.ingest(dispatchId, UUID.randomUUID(), 1,
                "WORKER_STARTED", envelope))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Sequence slot");
        // Ordered next event is fine
        assertThat(ingestion.ingest(dispatchId, UUID.randomUUID(), 2,
                "WORKER_COMPLETED", envelope))
                .isEqualTo(OrchestrationEventIngestionService.IngestResult.INSERTED);
    }

    // ── affinity (§16.4) ───────────────────────────────────────

    @Test
    @DisplayName("affinity: episodes/occurrences are durable; the recovery deadline never extends; reconnection clears")
    void affinityThrottling() {
        UUID runId = runFixture("aff").getId();
        UUID agentId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        Instant now = Instant.now();

        // First selection wins; a second call returns the recorded one.
        assertThat(affinity.recordCoordinator(runId, agentId, hostId)).isEqualTo(agentId);
        assertThat(affinity.recordCoordinator(runId, UUID.randomUUID(), hostId))
                .isEqualTo(agentId);

        // First unavailable observation: episode 1, occurrence 0, deadline starts.
        var first = affinity.observeUnavailable(runId, taskId, now);
        assertThat(first.episode()).isEqualTo(1);
        assertThat(first.occurrence()).isZero();
        assertThat(first.recoveryDeadline()).isAfter(now);
        Instant deadline = first.recoveryDeadline();

        // Repeated losses do NOT extend the deadline (H2 stores timestamps
        // at micro precision — compare with a 1-second tolerance).
        var second = affinity.observeUnavailable(runId, taskId, now.plusSeconds(60));
        assertThat(second.episode()).isEqualTo(1);
        assertThat(second.occurrence()).isEqualTo(1);
        assertThat(java.time.Duration.between(deadline, second.recoveryDeadline())
                .abs().toSeconds()).isZero();

        // Deterministic scheduling event ids differ per occurrence.
        assertThat(first.schedulingEventId()).isNotEqualTo(second.schedulingEventId());

        // Reconnection clears; a new outage starts a new episode.
        affinity.observeAvailable(runId);
        var third = affinity.observeUnavailable(runId, taskId, now.plusSeconds(120));
        assertThat(third.episode()).isEqualTo(2);
        assertThat(third.occurrence()).isZero();
        // The deadline is STILL the first-loss one (never restarted).
        assertThat(java.time.Duration.between(deadline, third.recoveryDeadline())
                .abs().toSeconds()).isZero();
        assertThat(affinity.isRecoveryExpired(runId, deadline.minusSeconds(1))).isFalse();
        assertThat(affinity.isRecoveryExpired(runId, deadline.plusSeconds(1))).isTrue();
    }

    // ── release (§16.5) ────────────────────────────────────────

    @Test
    @DisplayName("release: deterministic ids; acknowledged state lands on the run")
    void releaseEvidence() {
        UUID runId = runFixture("rel").getId();
        UUID releaseId = releaseService.releaseIdFor(runId, 2);

        // Deterministic: same run/generation → same releaseId.
        assertThat(releaseService.releaseIdFor(runId, 2)).isEqualTo(releaseId);

        var evidence = releaseService.recordAcknowledgement(
                runId, releaseId, 2, "RELEASED", Instant.now());
        // Deterministic derivation of ack + lifecycle ids.
        var replay = releaseService.recordAcknowledgement(
                runId, releaseId, 2, "RELEASED", Instant.now());
        assertThat(replay.acknowledgementId()).isEqualTo(evidence.acknowledgementId());
        assertThat(replay.lifecycleEventId()).isEqualTo(evidence.lifecycleEventId());

        var run = runRepository.findById(runId).orElseThrow();
        assertThat(run.getLeaseState()).isEqualTo("RELEASED");
        assertThat(run.getLeaseDeadline()).isNull();

        // The release frame is the bounded §16.5 shape.
        Map<String, Object> frame = releaseService.releaseFrame(runId, 3, "OPERATOR_STOP");
        assertThat(frame).containsKeys("releaseId", "runId", "workspaceGeneration", "reason");
    }

    // ── fixtures ──────────────────────────────────────────────

    record AttemptFixture(UUID attemptId, UUID taskId, UUID runId) {}

    private AttemptFixture attemptFixture(String tag) {
        var project = data.project().named(tag + "-proj").create();
        var profile = data.agentProfile().named(tag + "-profile").create();
        var admin = userRepository.findById(TEST_ADMIN_ID).orElseThrow();

        Workflow wf = new Workflow();
        wf.setProject(project);
        wf.setName(tag + "-wf-" + System.nanoTime());
        wf.setSteps(java.util.List.<Map<String, Object>>of());
        wf.setVersion(1);
        wf.setStatus(WorkflowStatus.PUBLISHED);
        wf.setCreatedBy(admin);
        wf = workflowRepository.save(wf);

        WorkflowRequest req = new WorkflowRequest();
        req.setWorkflow(wf);
        req.setWorkflowVersion(1);
        req.setInput(Map.of());
        req.setStatus(RequestStatus.RUNNING);
        req.setBranch("myrmec/" + tag);
        req.setCreatedBy(admin);
        req.setCreatedAt(Instant.now());
        req = requestRepository.save(req);

        WorkflowTask task = new WorkflowTask();
        task.setRequest(req);
        task.setStepId("build");
        task.setAgentProfile(profile);
        task.setInput(Map.of());
        task.setStatus(TaskStatus.RUNNING);
        task.setAttempt(1);
        task = taskRepository.save(task);

        TaskAttempt attempt = task.createAttempt(null);
        attempt = attemptRepository.save(attempt);
        return new AttemptFixture(attempt.getId(), task.getId(), req.getId());
    }

    private OrchestrationRun runFixture(String tag) {
        var project = data.project().named(tag + "-runproj").create();
        var profile = data.agentProfile().named(tag + "-runprofile").create();
        var admin = userRepository.findById(TEST_ADMIN_ID).orElseThrow();
        var wf = workflowRepository.save(workflowOf(project));

        // §16.7: orchestration_runs.id == the workflow request UUID — the
        // run IS the request from the orchestration domain's view.
        WorkflowRequest req = new WorkflowRequest();
        req.setWorkflow(wf);
        req.setWorkflowVersion(1);
        req.setInput(Map.of());
        req.setStatus(RequestStatus.RUNNING);
        req.setBranch("myrmec/" + tag);
        req.setCreatedBy(admin);
        req.setCreatedAt(Instant.now());
        req = requestRepository.save(req);

        OrchestrationRun run = OrchestrationRun.builder()
                .id(req.getId())
                .workflowId(wf.getId())
                .projectId(project.getId())
                .profileVersionId(publishedVersionOf(profile.getId()))
                .profileVersionDigest("d-" + tag)
                .build();
        return runRepository.save(run);
    }

    private java.util.UUID publishedVersionOf(UUID profileId) {
        return agentProfileVersionService.findPublished(profileId)
                .map(ai.myrmec.engine.agent.AgentProfileVersion::getId)
                .orElseThrow();
    }

    /** Resolve the project through the workflow's own transactional context. */
    private java.util.UUID projectIdOf(WorkflowRequest request) {
        Workflow workflow = workflowRepository.findById(request.getWorkflow().getId())
                .orElseThrow();
        return workflow.getProject().getId();
    }

    @Autowired
    private ai.myrmec.engine.agent.AgentProfileVersionService agentProfileVersionService;

    private Workflow workflowOf(ai.myrmec.engine.project.Project project) {
        Workflow wf = new Workflow();
        wf.setProject(project);
        wf.setName("run-wf-" + System.nanoTime());
        wf.setSteps(java.util.List.<Map<String, Object>>of());
        wf.setVersion(1);
        wf.setStatus(WorkflowStatus.PUBLISHED);
        wf.setCreatedBy(userRepository.findById(TEST_ADMIN_ID).orElseThrow());
        return wf;
    }
}