// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design §16.4 (6) respawn/restart recovery, re-pointed to the unified
 * protocol. The legacy resend-until-accept relay is gone, so a crashed worker
 * no longer receives a retransmission of the exact stored bytes. What survives
 * is the property that mattered:
 *
 * <ol>
 *   <li><b>the durable record survives the crash</b> — the dispatch's canonical
 *       bytes + digest stay in {@code orchestration_dispatches}, committed
 *       before any send, so nothing is lost with the socket;</li>
 *   <li><b>the resumed run is re-offered, not resumed half-open</b> — the
 *       orphaned session closes, the coordinator host's fresh instance takes a
 *       new offer, and the resumed attempt's assignment is again recorded
 *       durably before it is installed at {@code session.open};</li>
 *   <li><b>re-admission and terminals stay idempotent</b> — the §16.3 digest
 *       seam still fails closed on conflicting bytes and replays on a matching
 *       digest, and a duplicate terminal result replays the stored outcome
 *       without creating another attempt.</li>
 * </ol>
 */
class OrchestrationRespawnRecoveryTest extends WorkflowDispatchSupport {

    @Autowired private TaskDispatcherService dispatcher;
    @Autowired private OrchestrationRunService runService;
    @Autowired private OrchestrationRunRepository runRepository;
    @Autowired private OrchestrationDispatchRepository dispatchRepository;
    @Autowired private OrchestrationDispatchRelay dispatchRelay;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRequestRepository requestRepository;
    @Autowired private WorkflowTaskRepository taskRepository;
    @Autowired private TaskAttemptRepository attemptRepository;
    @Autowired private ai.myrmec.engine.websocket.InboundOrchestrationHandler inboundHandler;

    private Project project;
    private AgentProfile profile;
    private User admin;

    @BeforeEach
    void setUp() throws Exception {
        Path origin = Files.createTempDirectory("resp-origin-");
        origin = origin.resolve("origin.git");
        git(origin.getParent(), "init", "--bare", "-b", "main", origin.toString());
        Path seed = Files.createTempDirectory("resp-seed-");
        git(seed, "init", "-b", "main");
        git(seed, "-c", "user.email=t@t", "-c", "user.name=t",
                "commit", "--allow-empty", "-m", "seed");
        String originUrl = origin.toAbsolutePath().toString().replace('\\', '/');
        git(seed, "push", originUrl, "main");

        project = data.project().named("resp").withRepo(originUrl, "main").create();
        profile = data.agentProfile().named("resp-profile").create();
        admin = userRepository.findById(TEST_ADMIN_ID).orElseThrow();
    }

    @Test
    @DisplayName("worker crash mid-dispatch: the durable dispatch survives, the respawn gets exactly one fresh attempt, and a duplicate result never spawns another")
    void respawnReadmitsIdempotentlyFromTheStoredBytes() throws Exception {
        SocketHost host = openHost("resp-host", profile, project, 4);

        Workflow wf = workflowWithOrchestratorStep();
        WorkflowRequest request = runningRequest(wf, "resp");
        runService.pinRun(request.getId(), wf.getId(), project.getId(), profile.getId());
        WorkflowTask task = pendingTask(request);

        // ---- Dispatch #1: offered, assignment recorded BEFORE any send ----
        dispatcher.dispatchPendingTasks();
        UUID sessionId = offeredSessionId(host);
        TaskAttempt firstAttempt = attemptRepository
                .findByTaskIdOrderByAttemptNumberAsc(task.getId()).get(0);
        OrchestrationDispatch firstDispatch = dispatchRepository
                .findById(firstAttempt.getId()).orElseThrow();
        String firstDigest = firstDispatch.getAssignmentDigest();
        assertThat(firstDigest).hasSize(64);
        assertThat(firstDispatch.getDeliveryState())
                .as("nothing is delivered until the host has taken the slot")
                .isEqualTo("PENDING");

        // ---- The worker CRASHES before the handshake completes ----
        closeLiveInstances(host.host().getId());

        // The dispatch row — canonical bytes and digest — survives untouched:
        // that is the durable recovery substrate now.
        OrchestrationDispatch afterCrash = dispatchRepository
                .findById(firstAttempt.getId()).orElseThrow();
        assertThat(afterCrash.getAssignment()).isEqualTo(firstDispatch.getAssignment());
        assertThat(afterCrash.getAssignmentDigest()).isEqualTo(firstDigest);
        assertThat(attemptRepository.findByTaskIdOrderByAttemptNumberAsc(task.getId()))
                .as("the crash itself never spawns another attempt")
                .hasSize(1);

        // ---- Respawn: the host returns and the run re-offers ----
        closeSession(sessionId, "HOST_LOST");
        UUID respawnedInstance = reopenHost(host, 4);
        OrchestrationRun run = runRepository.findById(request.getId()).orElseThrow();
        run.setAvailabilityState("AVAILABLE");
        runRepository.save(run);

        // A respawned run resumes through the §16.6 retry re-dispatch (task back
        // to PENDING with a bumped attempt) — task_id + attempt_number is unique,
        // so a fresh attempt row is the only legal way to dispatch again.
        WorkflowTask reset = taskRepository.findById(task.getId()).orElseThrow();
        reset.setStatus(TaskStatus.PENDING);
        reset.setAttempt(reset.getAttempt() + 1);
        reset.setStartedAt(null);
        reset.setAgentInstance(null);
        taskRepository.save(reset);

        dispatcher.dispatchPendingTasks();

        // The run re-offered to the SAME coordinator host, and the resumed
        // attempt's assignment was recorded durably before the wire send.
        UUID reoffered = latestOfferedSessionId(host);
        assertThat(sessionRepository.findById(reoffered).orElseThrow().getHostInstanceId())
                .as("the respawn is re-offered onto the coordinator host's fresh instance")
                .isEqualTo(respawnedInstance);
        assertThat(agentHostInstanceRepository.findById(respawnedInstance).orElseThrow()
                .getAgentHostId())
                .as("and that instance belongs to the pinned coordinator HOST"
                        + " (§16.4 pin is host-level; the worker row is minted at session.opened)")
                .isEqualTo(host.host().getId());
        List<TaskAttempt> attempts =
                attemptRepository.findByTaskIdOrderByAttemptNumberAsc(task.getId());
        assertThat(attempts)
                .as("a respawn creates exactly one fresh attempt, not a duplicate")
                .hasSize(2);
        TaskAttempt resumed = attempts.get(1);
        OrchestrationDispatch resumedDispatch = dispatchRepository
                .findById(resumed.getId()).orElseThrow();
        assertThat(resumedDispatch.getAssignmentDigest()).hasSize(64);
        assertThat(resumedDispatch.getAssignmentDigest())
                .as("the resumed attempt is a genuinely new dispatch identity "
                        + "(§16.2: dispatchId is the attempt)")
                .isNotEqualTo(firstDigest);

        // session.open installs that attempt's STORED bytes — the digest the
        // engine recorded, never a re-serialised variant.
        acceptSession(host, reoffered);
        JsonNode sessionOpen = framesOf(host).stream()
                .filter(f -> "session.open".equals(f.path("type").asText()))
                .reduce((first, second) -> second).orElseThrow();
        assertThat(sessionOpen.path("payload").path("assignmentDigest").asText())
                .as("the digest installed at session.open is the one the engine stored")
                .isEqualTo(resumedDispatch.getAssignmentDigest());
        assertThat(sessionOpen.path("payload").path("orchestration").path("dispatch")
                .path("dispatchId").asText())
                .isEqualTo(resumed.getId().toString());
        openSession(host, reoffered);

        // ---- Re-admission stays idempotent at the §16.3 digest seam ----
        assertThat(dispatchRelay.accept(resumed.getId(), resumedDispatch.getAssignmentDigest()))
                .isTrue();
        assertThat(dispatchRelay.accept(resumed.getId(), resumedDispatch.getAssignmentDigest()))
                .as("duplicate accept replays the stored acknowledgement")
                .isTrue();
        assertThat(dispatchRelay.accept(resumed.getId(), "digest-forged"))
                .as("a conflicting digest still fails closed")
                .isFalse();
        assertThat(dispatchRepository.findById(resumed.getId()).orElseThrow()
                .getDeliveryState()).isEqualTo("ACCEPTED");
        assertThat(attemptRepository.findByTaskIdOrderByAttemptNumberAsc(task.getId()))
                .hasSize(2);

        // ---- A duplicate terminal result replays; no third attempt ----
        UUID resultId = UUID.randomUUID();
        JsonNode result = MAPPER.readTree(resultFrame(request.getId(), task.getId(),
                resumed.getId(), resultId, "COMPLETED", "respawned"));
        inboundHandler.onOrchestrationResult(host.host().getId(), result);
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(
                        attemptRepository.findById(resumed.getId()).orElseThrow().getStatus())
                        .isEqualTo(AttemptStatus.COMPLETED));
        inboundHandler.onOrchestrationResult(host.host().getId(), result);
        assertThat(attemptRepository.findById(resumed.getId()).orElseThrow()
                .getOrchestrationResultId()).isEqualTo(resultId);
        assertThat(attemptRepository.findByTaskIdOrderByAttemptNumberAsc(task.getId()))
                .as("a duplicate result never spawns another attempt")
                .hasSize(2);
    }

    // ── helpers ────────────────────────────────────────────────

    private UUID latestOfferedSessionId(SocketHost host) {
        return framesOf(host).stream()
                .filter(f -> "session.offer".equals(f.path("type").asText()))
                .reduce((first, second) -> second)
                .map(f -> UUID.fromString(f.path("payload").path("sessionId").asText()))
                .orElseThrow();
    }

    private void closeSession(UUID sessionId, String reason) {
        sessionRepository.findById(sessionId).ifPresent(session -> {
            session.setAllocationState("CLOSED");
            session.setClosedAt(Instant.now());
            sessionRepository.save(session);
        });
    }

    private String resultFrame(UUID runId, UUID taskId, UUID dispatchId,
                               UUID resultId, String status, String summary) {
        return """
                {"resultId":"%s","resultDigest":"%s","runId":"%s",\
                "dispatch":{"workflowId":"wf-1","runId":"%s","stepId":"build",\
                "taskId":"%s","attemptId":"%s","attemptOrdinal":1,"dispatchId":"%s"},\
                "status":"%s","retryDisposition":"NONE","summary":"%s",\
                "usage":{"workerCalls":0,"rejectionCount":0,"totalTokens":0}}
                """.formatted(resultId, "digest-" + resultId, runId, runId, taskId,
                dispatchId, dispatchId, status, summary);
    }

    // ── fixtures ───────────────────────────────────────────────

    private WorkflowTask pendingTask(WorkflowRequest request) {
        WorkflowTask task = new WorkflowTask();
        task.setRequest(request);
        task.setStepId("build");
        task.setAgentProfile(profile);
        task.setInput(Map.of());
        task.setStatus(TaskStatus.PENDING);
        task.setAttempt(1);
        task.setMaxRetries(1);
        return taskRepository.save(task);
    }

    private WorkflowRequest runningRequest(Workflow wf, String tag) {
        WorkflowRequest req = new WorkflowRequest();
        req.setWorkflow(wf);
        req.setWorkflowVersion(1);
        req.setInput(Map.of());
        req.setStatus(RequestStatus.RUNNING);
        req.setBranch("myrmec/" + tag);
        req.setCreatedBy(admin);
        req.setCreatedAt(Instant.now());
        return requestRepository.save(req);
    }

    private Workflow workflowWithOrchestratorStep() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", "build");
        step.put("name", "Build");
        step.put("agentProfileId", profile.getId().toString());
        step.put("prompt", "Orchestrate");
        step.put("dependsOn", List.of());
        step.put("transitions", Map.of());
        step.put("timeoutSeconds", 600);
        step.put("maxRetries", 1);
        step.put("pauseMode", "NONE");
        step.put("taskType", "ORCHESTRATOR");
        Map<String, Object> orch = new LinkedHashMap<>();
        orch.put("modelCode", TEST_MODEL_CODE);
        orch.put("goal", "write a module");
        orch.put("sourceSubPath", ".");
        orch.put("workers", List.of());
        orch.put("checkpointStrategy", Map.of(
                "mode", "ON_VERIFICATION_PASS", "commitMessage", "checkpoint",
                "pushToRemote", false, "allowNoChanges", true));
        orch.put("completionCriteria", Map.of(
                "definitionOfDone", "compiles", "requireVerificationBy", List.of()));
        orch.put("budget", Map.of(
                "maxTokens", 50000, "maxWorkerCalls", 10,
                "maxVerifierRejectionsPerAttempt", 3,
                "maxOrchestratorIterations", 5, "maxWorkerIterations", 5,
                "onBudgetExceeded", "FAIL"));
        step.put("orchestration", orch);
        step.put("retryPolicy", Map.of(
                "maxRetries", 1, "initialBackoffSeconds", 2, "maxBackoffSeconds", 30));

        Workflow wf = new Workflow();
        wf.setProject(project);
        wf.setName("resp-wf-" + System.nanoTime());
        wf.setSteps(List.of(step));
        wf.setVersion(1);
        wf.setStatus(WorkflowStatus.PUBLISHED);
        wf.setCreatedBy(admin);
        return workflowRepository.save(wf);
    }

    private static void git(Path cwd, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + args[0] + " failed: "
                    + new String(p.getInputStream().readAllBytes()));
        }
    }
}
