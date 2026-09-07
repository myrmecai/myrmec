// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HITL slice B (design §16.6/§17.4): the orchestration-aware decision
 * path. Approve resets the task to PENDING with a fresh attempt ordinal,
 * enriches the stored payload with the typed decision envelope fields,
 * and restores the request to RUNNING. Rejection and expiry apply the
 * engine-owned terminal tuple — original attempt stays PAUSED with its
 * output immutable, task COMPLETED/FAILURE with the decision's error
 * code, request FAILED, no continuation attempt. The triggering-user
 * gate (§17.4) rejects every other decider.
 */
@DisplayName("HITL-B: OrchestrationApprovalService (§16.6/§17.4)")
class OrchestrationApprovalServiceTest extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private OrchestrationApprovalService approvalService;

    @Autowired
    private TaskAttemptRepository attemptRepository;
    @Autowired
    private WorkflowTaskRepository taskRepository;
    @Autowired
    private WorkflowRequestRepository requestRepository;
    @Autowired
    private WorkflowRepository workflowRepository;

    private WorkflowRequest request;
    private WorkflowTask task;
    private TaskAttempt pausedAttempt;
    private User triggerer;

    @BeforeEach
    void setUp() {
        Project project = data.project().named("hitl").withRepo("https://x.git", "main").create();
        AgentProfile profile = data.agentProfile().named("hitl-profile").create();
        triggerer = userRepository.findById(TEST_ADMIN_ID).orElseThrow();

        Workflow wf = new Workflow();
        wf.setProject(project);
        wf.setName("hitl-wf-" + System.nanoTime());
        wf.setSteps(java.util.List.<Map<String, Object>>of());
        wf.setVersion(1);
        wf.setStatus(WorkflowStatus.PUBLISHED);
        wf.setCreatedBy(triggerer);
        wf = workflowRepository.save(wf);

        WorkflowRequest req = new WorkflowRequest();
        req.setWorkflow(wf);
        req.setWorkflowVersion(1);
        req.setInput(Map.of());
        req.setStatus(RequestStatus.PAUSED);
        req.setBranch("myrmec/hitl");
        req.setCreatedBy(triggerer);
        req.setCreatedAt(Instant.now());
        request = requestRepository.save(req);

        task = new WorkflowTask();
        task.setRequest(request);
        task.setStepId("build");
        task.setAgentProfile(profile);
        task.setInput(Map.of());
        task.setStatus(TaskStatus.PAUSED);
        task.setAttempt(1);
        task.setMaxRetries(1);
        task.setPauseState("ORCH_REVIEW");
        task.setPausedAt(Instant.now());
        task.setApprovalStatus("PENDING");
        task.setApprovalRequestedAt(Instant.now());
        task.setApprovalExpiresAt(Instant.parse("2099-01-01T00:00:00Z"));
        task = taskRepository.save(task);

        pausedAttempt = task.createAttempt(null);
        pausedAttempt.setStatus(AttemptStatus.PAUSED);
        pausedAttempt = attemptRepository.save(pausedAttempt);
        task = taskRepository.findById(task.getId()).orElseThrow();

        // The stored §16.3 approval payload (from
        // onOrchestrationApprovalRequested) + the §7.3 suspension record
        // mirrored to task output by the outcome path.
        Map<String, Object> payload = new HashMap<>();
        payload.put("approvalRequestId", UUID.randomUUID().toString());
        payload.put("action", Map.of(
                "actionId", "action-1",
                "type", "WORKER_TOOL",
                "riskClass", "DESTRUCTIVE",
                "summary", "worker:impl:edit",
                "digest", "a".repeat(64)));
        payload.put("stateDigest", "b".repeat(64));
        payload.put("snapshotTreeHash", "c".repeat(40));
        payload.put("expiresAt", "2099-01-01T00:00:00Z");
        task.setApprovalPayload(payload);

        Map<String, Object> suspension = new HashMap<>();
        suspension.put("continuationId", "cont-" + pausedAttempt.getId());
        suspension.put("continuationRef", "local:cont");
        suspension.put("snapshotTreeHash", "c".repeat(40));
        suspension.put("workspaceRevision", 2);
        suspension.put("stateDigest", "b".repeat(64));
        suspension.put("reason", "HITL_APPROVAL");
        suspension.put("approvalRequestId", payload.get("approvalRequestId"));
        suspension.put("expiresAt", "2099-01-01T00:00:00Z");
        Map<String, Object> output = new HashMap<>();
        output.put("summary", "suspended for approval");
        output.put("suspension", suspension);
        task.setOutput(output);
        task = taskRepository.save(task);
    }

    // ── approve: the resume path ─────────────────────────────

    @Test
    @DisplayName("APPROVED → task PENDING with attempt+1, request RUNNING, decision evidence stored")
    void approveResetsForContinuation() {
        UUID decisionBefore = UUID.randomUUID(); // sanity: distinct ids
        approvalService.decide(task.getId(), "APPROVED", triggerer.getId());
        assertThat(decisionBefore).isNotNull();

        WorkflowTask stored = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(stored.getAttempt()).isEqualTo(2);
        assertThat(stored.getApprovalStatus()).isEqualTo("APPROVED");
        // §16.6: pauseState returns to the NONE baseline (never null —
        // the J3 progression gate treats null as "never evaluated").
        assertThat(stored.getPauseState()).isEqualTo("NONE");
        assertThat(stored.getPausedAt()).isNull();
        assertThat(stored.getPauseReason()).isNull();
        assertThat(stored.getAgentInstance()).isNull();
        assertThat(stored.getNextEligibleAt()).isNull();

        assertThat(requestRepository.findById(request.getId()).orElseThrow()
                .getStatus()).isEqualTo(RequestStatus.RUNNING);

        // The original attempt keeps its PAUSED state + immutable output
        // (the JsonMapConverter materializes null output as an empty map —
        // key on the attempt's status and output null-ness/emptiness).
        TaskAttempt prior = attemptRepository.findById(pausedAttempt.getId()).orElseThrow();
        assertThat(prior.getStatus()).isEqualTo(AttemptStatus.PAUSED);
        assertThat(prior.getOutput()).isNotNull();

        // §17.4 decision evidence: the §16.2 assembler builds the typed
        // ApprovalDecision envelope from these fields.
        Map<String, Object> payload = stored.getApprovalPayload();
        assertThat(payload.get("decisionStatus")).isEqualTo("APPROVED");
        assertThat(payload.get("decidedBy")).isEqualTo(triggerer.getId().toString());
        assertThat(payload.get("decisionId").toString()).isNotBlank();
        assertThat(payload.get("previousDispatchId"))
                .isEqualTo(pausedAttempt.getId().toString());
        assertThat(payload.get("suspensionContinuationId"))
                .isEqualTo("cont-" + pausedAttempt.getId());
        assertThat(payload.get("stateDigest")).isEqualTo("b".repeat(64));
        assertThat(payload.get("actionDigest")).isEqualTo("a".repeat(64));
        assertThat(payload.get("approvalRequestId").toString()).isNotBlank();
    }

    // ── reject: the terminal tuple ───────────────────────────

    @Test
    @DisplayName("REJECTED → task COMPLETED/FAILURE + APPROVAL_REJECTED, request FAILED, attempt stays PAUSED")
    void rejectAppliesTerminalTuple() {
        var outcome = approvalService.decide(task.getId(), "REJECTED", triggerer.getId());
        assertThat(outcome).isEqualTo(OrchestrationApprovalService.DecisionOutcome.REJECTED);

        WorkflowTask stored = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(stored.getResult()).isEqualTo(TaskResult.FAILURE);
        assertThat(stored.getErrorMessage()).isEqualTo("APPROVAL_REJECTED");
        assertThat(stored.getApprovalStatus()).isEqualTo("REJECTED");
        // §16.6: pause fields clear — pauseState returns to the NONE
        // baseline (never null; the J3 gate treats null as unevaluated).
        assertThat(stored.getPauseState()).isEqualTo("NONE");
        assertThat(stored.getCompletedAt()).isNotNull();
        assertThat(stored.getNextEligibleAt()).isNull();

        assertThat(requestRepository.findById(request.getId()).orElseThrow()
                .getStatus()).isEqualTo(RequestStatus.FAILED);

        // §16.6: the original PAUSED attempt keeps its signed output immutable
        // (stored on the attempt by the outcome path; the fixture mirrors
        // the suspension to task output — the attempt row never regresses).
        TaskAttempt prior = attemptRepository.findById(pausedAttempt.getId()).orElseThrow();
        assertThat(prior.getStatus()).isEqualTo(AttemptStatus.PAUSED);
        assertThat(prior.getOutput()).isNotNull();

        // No continuation attempt: the attempt counter did not advance.
        assertThat(stored.getAttempt()).isEqualTo(1);
    }

    // ── expiry ───────────────────────────────────────────────

    @Test
    @DisplayName("expire → the same terminal tuple with APPROVAL_EXPIRED")
    void expireAppliesTerminalTuple() {
        var outcome = approvalService.expire(task.getId());
        assertThat(outcome).isEqualTo(OrchestrationApprovalService.DecisionOutcome.EXPIRED);

        WorkflowTask stored = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(stored.getResult()).isEqualTo(TaskResult.FAILURE);
        assertThat(stored.getErrorMessage()).isEqualTo("APPROVAL_EXPIRED");
        assertThat(stored.getApprovalStatus()).isEqualTo("EXPIRED");
        assertThat(requestRepository.findById(request.getId()).orElseThrow()
                .getStatus()).isEqualTo(RequestStatus.FAILED);
    }

    // ── the triggering-user gate (§17.4) ─────────────────────

    @Test
    @DisplayName("a decision from any user other than the triggering user is rejected")
    void triggeringUserGate() {
        UUID otherUser = UUID.randomUUID();
        assertThatThrownBy(() ->
                approvalService.decide(task.getId(), "APPROVED", otherUser))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("triggering user");

        // State untouched — still PENDING under the lock.
        assertThat(taskRepository.findById(task.getId()).orElseThrow()
                .getApprovalStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("a task that is not in ORCH_REVIEW is refused")
    void nonOrchReviewRefused() {
        WorkflowTask stored = taskRepository.findById(task.getId()).orElseThrow();
        stored.setPauseState("NONE");
        taskRepository.save(stored);

        assertThatThrownBy(() ->
                approvalService.decide(task.getId(), "APPROVED", triggerer.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not an orchestration review");
    }

    @Test
    @DisplayName("an already-decided approval is refused")
    void alreadyDecidedRefused() {
        approvalService.decide(task.getId(), "REJECTED", triggerer.getId());

        assertThatThrownBy(() ->
                approvalService.decide(task.getId(), "APPROVED", triggerer.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already decided");
    }

    @Test
    @DisplayName("an approve without a stored suspension continuation fails closed")
    void approveWithoutSuspensionFailsClosed() {
        WorkflowTask stored = taskRepository.findById(task.getId()).orElseThrow();
        stored.setOutput(Map.of("summary", "no suspension here"));
        taskRepository.save(stored);

        assertThatThrownBy(() ->
                approvalService.decide(task.getId(), "APPROVED", triggerer.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no suspension");
    }
}