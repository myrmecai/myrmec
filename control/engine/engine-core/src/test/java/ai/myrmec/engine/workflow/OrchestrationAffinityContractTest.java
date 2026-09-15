// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionAllocator;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design §16.4 agent-affinity contract (design's own named integration
 * tests): two eligible hosts — every dispatch of a run goes to the host of the
 * first-selected coordinator; a pinned-but-unavailable coordinator throttles
 * with bounded backoff and durable episodes; reconnect clears; the pinned
 * recovery deadline never extends; expiry is terminal WORKSPACE_LOST.
 *
 * <p><b>Binding after the cutover.</b> The coordinator binding is the session's
 * {@code host_instance_id} (§16.4's "session host binding"): the run records the
 * selected host, and every later dispatch of that run offers its session to that
 * host's live instance — the allocator is the only thing that mints serving
 * workers now, so the pin is a host pin rather than a pre-minted agent-instance
 * pin. When the pinned host has no live/capacity-bearing instance the task stays
 * PENDING with the same durable throttling, and the same deadline semantics
 * apply.</p>
 */
class OrchestrationAffinityContractTest extends WorkflowDispatchSupport {

    @Autowired private TaskDispatcherService dispatcher;
    @Autowired private OrchestrationRunService runService;
    @Autowired private OrchestrationRunRepository runRepository;
    @Autowired private OrchestrationAffinityResolver affinity;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRequestRepository requestRepository;
    @Autowired private WorkflowTaskRepository taskRepository;
    @Autowired private TaskAttemptRepository attemptRepository;
    @Autowired private ExecutionEventRepository eventRepository;
    @Autowired private ai.myrmec.engine.inference.SessionRepository sessionRepository;

    private Project project;
    private AgentProfile profile;
    private User admin;

    @BeforeEach
    void setUp() throws Exception {
        // The assembler resolves the source base with a REAL git ls-remote —
        // the project repo must be a seeded local bare origin.
        java.nio.file.Path origin = java.nio.file.Files.createTempDirectory("aff-origin-");
        origin = origin.resolve("origin.git");
        git(origin.getParent(), "init", "--bare", "-b", "main", origin.toString());
        java.nio.file.Path seed = java.nio.file.Files.createTempDirectory("aff-seed-");
        git(seed, "init", "-b", "main");
        git(seed, "-c", "user.email=t@t", "-c", "user.name=t",
                "commit", "--allow-empty", "-m", "seed");
        String originUrl = origin.toAbsolutePath().toString().replace('\\', '/');
        git(seed, "push", originUrl, "main");

        project = data.project().named("aff").withRepo(originUrl, "main").create();
        profile = data.agentProfile().named("aff-profile").create();
        admin = userRepository.findById(TEST_ADMIN_ID).orElseThrow();
    }

    private static void git(java.nio.file.Path cwd, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(java.util.Arrays.asList(args));
        Process p = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + args[0] + " failed: "
                    + new String(p.getInputStream().readAllBytes()));
        }
    }

    // ── (a) two-host affinity: §16.4's own scenario ─────────────

    @Test
    @DisplayName("two eligible hosts: every orchestration dispatch of a run binds to the first-selected coordinator host")
    void twoHostAffinityPinsFirstCoordinatorHost() throws Exception {
        Workflow wf = workflowWithOrchestratorStep("aff-two");
        SocketHost hostA = openHost("aff-host-a", profile, project, 4);
        SocketHost hostB = openHost("aff-host-b", profile, project, 4);

        // First dispatch of a multi-step request pins hostA or hostB; whichever
        // it is, the SECOND task of the same request must bind to the same host
        // even though the other is equally eligible.
        WorkflowRequest request = runningRequest(wf, "aff-two");
        pinRun(request);
        WorkflowTask first = pendingTask(request, "build");

        dispatcher.dispatchPendingTasks();

        OrchestrationRun run = runRepository.findById(request.getId()).orElseThrow();
        UUID coordinatorHost = run.getCoordinatorHostId();
        assertThat(coordinatorHost).as("the first dispatch pins a coordinator host").isNotNull();
        assertThat(coordinatorHost).isIn(hostA.host().getId(), hostB.host().getId());

        // Complete the handshake so the run's first session is ACTIVE and the
        // second task can be offered on the same host.
        SocketHost pinned = coordinatorHost.equals(hostA.host().getId()) ? hostA : hostB;
        SocketHost other = pinned == hostA ? hostB : hostA;
        completeHandshake(pinned);
        assertThat(attemptRepository.findByTaskIdOrderByAttemptNumberAsc(first.getId()).get(0)
                .getAgentInstance()).isNotNull();

        WorkflowTask second = pendingTask(request, "verify");
        dispatcher.dispatchPendingTasks();

        // The second task was offered to the PINNED host, never the other one.
        assertThat(framesOf(other).stream().map(f -> f.path("type").asText()).toList())
                .as("the equally-eligible host receives no offer for this run")
                .doesNotContain("session.offer");
        Session secondSession = sessionRepository.findByRefId(request.getId()).stream()
                .filter(s -> SessionAllocator.ALLOC_STATE_OFFERED.equals(s.getAllocationState()))
                .findFirst().orElseThrow();
        assertThat(secondSession.getHostInstanceId()).isEqualTo(pinned.instanceId());
        assertThat(runRepository.findById(request.getId()).orElseThrow().getCoordinatorHostId())
                .isEqualTo(coordinatorHost);
        assertThat(attemptRepository.findByTaskId(second.getId())).isNotEmpty();
    }

    private TaskAttempt firstAttempt(WorkflowRequest request) {
        return attemptRepository.findAll().stream()
                .filter(a -> a.getTask().getRequest().getId().equals(request.getId()))
                .findFirst().orElseThrow();
    }

    // ── (b) availability episodes + throttled backoff ───────────

    @Test
    @DisplayName("a pinned-but-unavailable coordinator: throttled AGENT_UNAVAILABLE events, bounded backoff, no attempt consumed")
    void unavailableCoordinatorThrottlesWithBackoff() throws Exception {
        Workflow wf = workflowWithOrchestratorStep("aff-backoff");
        SocketHost hostA = openHost("aff-host-c", profile, project, 4);

        WorkflowRequest request = runningRequest(wf, "aff-backoff");
        pinRun(request);
        WorkflowTask task = pendingTask(request, "build");

        // First pass selects + pins the coordinator host.
        dispatcher.dispatchPendingTasks();
        OrchestrationRun run = runRepository.findById(request.getId()).orElseThrow();
        assertThat(run.getCoordinatorHostId()).isEqualTo(hostA.host().getId());
        int attemptsBefore = attemptRepository.findByTaskId(task.getId()).size();

        // The coordinator goes down: close its live instance so the host has no
        // OPEN instance left (the §7.1 capacity probe misses).
        closeLiveInstances(hostA.host().getId());

        // §16.6 retryable reset: the SAME task row returns to PENDING
        // (bumped attempt) for the coordinator to pick back up.
        task.setStatus(TaskStatus.PENDING);
        task.setAttempt(task.getAttempt() + 1);
        task.setStartedAt(null);
        task.setAgentInstance(null);
        WorkflowTask retried = taskRepository.save(task);

        // Eligible pass #1: episode 1, occurrence 0, backoff applied, throttled
        // event persisted, NO NEW attempt.
        dispatcher.dispatchPendingTasks();

        run = runRepository.findById(request.getId()).orElseThrow();
        assertThat(run.getAvailabilityState()).isEqualTo("UNAVAILABLE");
        assertThat(run.getAvailabilityEpisode()).isEqualTo(1);
        assertThat(run.getAvailabilityOccurrence()).isEqualTo(0);

        WorkflowTask stored = taskRepository.findById(retried.getId()).orElseThrow();
        assertThat(stored.getStatus())
                .as("the task stays PENDING while the coordinator is down (§16.4 (4))")
                .isEqualTo(TaskStatus.PENDING);
        assertThat(stored.getNextEligibleAt()).isAfter(Instant.now());
        assertThat(attemptRepository.findByTaskId(retried.getId()))
                .as("an unavailable coordinator must never consume an attempt")
                .hasSize(attemptsBefore);

        // The throttled scheduling event: deterministic id, SYSTEM source.
        UUID expectedEvent = OrchestrationIds.schedulingEventId(
                request.getId(), retried.getId(), 1, 0);
        ExecutionEvent scheduling = eventRepository
                .findBySourceEventId(expectedEvent).orElseThrow();
        assertThat(scheduling.getSource()).isEqualTo(LogSource.SYSTEM);
        assertThat(scheduling.getEventType()).isEqualTo(EventType.ORCHESTRATION);
        assertThat(scheduling.getAttemptId()).isNull();
        assertThat(scheduling.getSequenceNumber()).isNull();
        assertThat(scheduling.getData()).containsEntry("type", "AGENT_UNAVAILABLE");

        // A pass before nextEligibleAt: NOTHING new (occurrence stays 0).
        dispatcher.dispatchPendingTasks();
        run = runRepository.findById(request.getId()).orElseThrow();
        assertThat(run.getAvailabilityOccurrence()).isEqualTo(0);

        // Reconnect: the host comes back with a fresh live instance — the
        // §16.4 (7) reconnect proof clears the availability condition and the
        // next eligible pass dispatches again.
        reopenHost(hostA, 4);
        affinity.observeAvailable(request.getId());
        WorkflowTask eligible = taskRepository.findById(retried.getId()).orElseThrow();
        eligible.setNextEligibleAt(null);
        taskRepository.save(eligible);

        dispatcher.dispatchPendingTasks();
        run = runRepository.findById(request.getId()).orElseThrow();
        assertThat(run.getAvailabilityState()).isEqualTo("AVAILABLE");
        assertThat(sessionRepository.findByRefId(request.getId()).stream()
                .anyMatch(s -> SessionAllocator.ALLOC_STATE_OFFERED.equals(s.getAllocationState())
                        || SessionAllocator.ALLOC_STATE_ACTIVE.equals(s.getAllocationState())))
                .as("the recovered coordinator host takes the task again")
                .isTrue();
    }

    // ── (c) terminal loss after the pinned deadline ─────────────

    @Test
    @DisplayName("recovery-deadline expiry without reconnect: run LOST, request FAILED with engine-generated WORKSPACE_LOST")
    void recoveryExpiryIsTerminalWorkspaceLost() throws Exception {
        Workflow wf = workflowWithOrchestratorStep("aff-lost");
        SocketHost hostA = openHost("aff-host-d", profile, project, 4);

        WorkflowRequest request = runningRequest(wf, "aff-lost");
        pinRun(request);
        WorkflowTask task = pendingTask(request, "build");

        // Pin the coordinator host, then take its live instance down.
        dispatcher.dispatchPendingTasks();
        OrchestrationRun run = runRepository.findById(request.getId()).orElseThrow();
        assertThat(run.getCoordinatorHostId()).isEqualTo(hostA.host().getId());
        closeLiveInstances(hostA.host().getId());

        // Start the outage (episode 1) — the deadline starts now. The same task
        // row resets to PENDING (retryable semantics).
        task.setStatus(TaskStatus.PENDING);
        task.setAttempt(task.getAttempt() + 1);
        task.setStartedAt(null);
        task.setAgentInstance(null);
        WorkflowTask retried = taskRepository.save(task);
        dispatcher.dispatchPendingTasks();
        run = runRepository.findById(request.getId()).orElseThrow();
        assertThat(run.getAffinityRecoveryDeadline()).isNotNull();

        // Expire the deadline: move it into the past.
        run.setAffinityRecoveryDeadline(Instant.now().minusSeconds(1));
        runRepository.save(run);

        // The next eligible pass applies terminal loss.
        WorkflowTask afterExpiry = taskRepository.findById(retried.getId()).orElseThrow();
        afterExpiry.setNextEligibleAt(null);
        taskRepository.save(afterExpiry);
        dispatcher.dispatchPendingTasks();

        run = runRepository.findById(request.getId()).orElseThrow();
        assertThat(run.getLeaseState()).isEqualTo("LOST");
        assertThat(requestRepository.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(RequestStatus.FAILED);
        WorkflowTask failed = taskRepository.findById(retried.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(failed.getResult()).isEqualTo(TaskResult.FAILURE);
        assertThat(failed.getErrorMessage()).isEqualTo("WORKSPACE_LOST");
        assertThat(failed.getNextEligibleAt()).isNull();
    }

    // ── helpers ────────────────────────────────────────────────

    // ── fixtures ───────────────────────────────────────────────

    /** A real WorkflowTask row for a PENDING dispatch pass. */
    private WorkflowTask pendingTask(WorkflowRequest request, String stepId) {
        WorkflowTask task = new WorkflowTask();
        task.setRequest(request);
        task.setStepId(stepId);
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

    private void pinRun(WorkflowRequest request) {
        runService.pinRun(request.getId(),
                request.getWorkflow().getId(), project.getId(), profile.getId());
    }

    private Workflow workflowWithOrchestratorStep(String tag) {
        Map<String, Object> build = orchestratorStep("build");
        Map<String, Object> verify = orchestratorStep("verify");
        Workflow wf = new Workflow();
        wf.setProject(project);
        wf.setName(tag + "-wf-" + System.nanoTime());
        wf.setSteps(List.of(build, verify));
        wf.setVersion(1);
        wf.setStatus(WorkflowStatus.PUBLISHED);
        wf.setCreatedBy(admin);
        return workflowRepository.save(wf);
    }

    private Map<String, Object> orchestratorStep(String id) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", id);
        step.put("name", id);
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
        return step;
    }
}
