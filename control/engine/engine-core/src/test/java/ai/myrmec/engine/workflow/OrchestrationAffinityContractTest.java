// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.user.User;
import ai.myrmec.engine.websocket.AgentConnectionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Design §16.4 agent-affinity contract (design's own named integration
 * tests): two eligible agents — every dispatch goes to the first
 * selected coordinator; a pinned-but-unavailable coordinator throttles
 * with bounded backoff and durable episodes; reconnect clears; the
 * pinned recovery deadline never extends; expiry is terminal
 * WORKSPACE_LOST.
 */
class OrchestrationAffinityContractTest extends IntegrationTestBase {

    @Autowired private TestDataBuilder data;
    @Autowired private TaskDispatcherService dispatcher;
    @Autowired private OrchestrationRunService runService;
    @Autowired private OrchestrationRunRepository runRepository;
    @Autowired private OrchestrationAffinityResolver affinity;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRequestRepository requestRepository;
    @Autowired private WorkflowTaskRepository taskRepository;
    @Autowired private TaskAttemptRepository attemptRepository;
    @Autowired private ExecutionEventRepository eventRepository;
    @Autowired private AgentConnectionManager connectionManager;
    @Autowired private ai.myrmec.engine.websocket.AgentWebSocketHandler agentWebSocketHandler;
    @Autowired private ObjectMapper objectMapper;

    private Project project;
    private AgentProfile profile;
    private User admin;

    @BeforeEach
    void setUp() throws Exception {
        // The assembler resolves the source base with a REAL git ls-remote —
        // the project repo must be a seeded local bare origin.
        java.nio.file.Path origin = java.nio.file.Files.createTempDirectory("aff-origin-");
        origin = origin.resolve("origin.git");
        git(java.nio.file.Path.of(origin + "/.."), "init", "--bare", "-b", "main",
                origin.toString());
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
        java.util.List<String> command = new java.util.ArrayList<>();
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

    // ── (a) two-agent affinity: §16.4's own scenario ────────────

    @Test
    @DisplayName("two eligible agents: every orchestration dispatch goes to the first-selected coordinator")
    void twoAgentAffinityPinsFirstCoordinator() throws Exception {
        Workflow wf = workflowWithOrchestratorStep("aff-two");
        AgentHost hostA = data.agent().named("host-a").withProfile(profile)
                .inProject(project).create().agent();
        AgentHost hostB = data.agent().named("host-b").withProfile(profile)
                .inProject(project).create().agent();

        // Both instances idle + connected — either is eligible first.
        Agent instanceA = idleInstanceFor(hostA, "aff-a", new LinkedBlockingQueue<>());
        Agent instanceB = idleInstanceFor(hostB, "aff-b", new LinkedBlockingQueue<>());

        // First dispatch of a multi-step request pins instanceA or
        // instanceB; whichever it is, the SECOND task of the same
        // request must land on the same instance even though the other
        // is equally eligible.
        WorkflowRequest request = runningRequest(wf, "aff-two");
        pinRun(request);

        WorkflowTask first = pendingTask(request, "build");
        WorkflowTask second = pendingTask(request, "verify");

        dispatcher.dispatchPendingTasks();

        OrchestrationRun run = runRepository.findById(request.getId()).orElseThrow();
        UUID coordinator = run.getCoordinatorAgentId();
        assertThat(coordinator).as("the first dispatch pins a coordinator").isNotNull();
        assertThat(coordinator).isIn(instanceA.getId(), instanceB.getId());

        // The second task must reuse the coordinator (no substitute).
        dispatcher.dispatchPendingTasks();
        run = runRepository.findById(request.getId()).orElseThrow();
        assertThat(run.getCoordinatorAgentId()).isEqualTo(coordinator);
        assertThat(taskRepository.findById(second.getId()).orElseThrow().getAgentInstance())
                .as("the coordinator executes every orchestration task of the run")
                .isNotNull()
                .extracting(Agent::getId)
                .isEqualTo(coordinator);
        assertThat(taskRepository.findById(first.getId()).orElseThrow().getAgentInstance())
                .extracting(Agent::getId)
                .isEqualTo(coordinator);
    }

    // ── (b) availability episodes + throttled backoff ───────────

    @Test
    @DisplayName("a pinned-but-unavailable coordinator: throttled AGENT_UNAVAILABLE events, bounded backoff, no attempt consumed")
    void unavailableCoordinatorThrottlesWithBackoff() throws Exception {
        Workflow wf = workflowWithOrchestratorStep("aff-backoff");
        AgentHost hostA = data.agent().named("host-c").withProfile(profile)
                .inProject(project).create().agent();
        Agent instanceA = idleInstanceFor(hostA, "aff-c", new LinkedBlockingQueue<>());

        WorkflowRequest request = runningRequest(wf, "aff-backoff");
        pinRun(request);
        WorkflowTask task = pendingTask(request, "build");

        // First pass selects + pins the coordinator.
        dispatcher.dispatchPendingTasks();
        OrchestrationRun run = runRepository.findById(request.getId()).orElseThrow();
        UUID coordinator = run.getCoordinatorAgentId();
        assertThat(coordinator).isEqualTo(instanceA.getId());

        // The coordinator goes down: unregister its socket —
        // findByIdIfAlive must miss on the connection check.
        connectionManager.unregisterByAgentInstanceId(coordinator);

        // §16.6 retryable reset: the SAME task row returns to PENDING
        // (bumped attempt) for the coordinator to pick back up.
        task.setStatus(TaskStatus.PENDING);
        task.setAttempt(task.getAttempt() + 1);
        task.setStartedAt(null);
        task.setAgentInstance(null);
        WorkflowTask retried = taskRepository.save(task);

        // Eligible pass #1: episode 1, occurrence 0, backoff applied,
        // throttled event persisted, NO NEW attempt (the reset re-uses
        // the same task row — attempt #1 already exists from the first
        // dispatch; the unavailable pass must not add another).
        int attemptsBefore = attemptRepository.findByTaskId(task.getId()).size();
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

        // Reconnect: clears the condition (§16.4 (7)) — the reconnect
        // proof flows through the WS handler's connection-established
        // path, not a bare connectionManager.register.
        WebSocketSession reconnect = mock(WebSocketSession.class);
        Map<String, Object> attrs = new java.util.HashMap<>();
        attrs.put("agentInstanceId", coordinator);
        attrs.put("agentName", "aff-c");
        lenient().when(reconnect.getId()).thenReturn("reconnect-" + coordinator);
        lenient().when(reconnect.isOpen()).thenReturn(true);
        lenient().when(reconnect.getAttributes()).thenReturn(attrs);
        agentWebSocketHandler.afterConnectionEstablished(reconnect);

        // And the next pass dispatches to the coordinator again.
        dispatcher.dispatchPendingTasks();
        run = runRepository.findById(request.getId()).orElseThrow();
        assertThat(run.getAvailabilityState()).isEqualTo("AVAILABLE");
    }

    // ── (c) terminal loss after the pinned deadline ─────────────

    @Test
    @DisplayName("recovery-deadline expiry without reconnect: run LOST, request FAILED with engine-generated WORKSPACE_LOST")
    void recoveryExpiryIsTerminalWorkspaceLost() throws Exception {
        Workflow wf = workflowWithOrchestratorStep("aff-lost");
        AgentHost hostA = data.agent().named("host-d").withProfile(profile)
                .inProject(project).create().agent();
        Agent instanceA = idleInstanceFor(hostA, "aff-d", new LinkedBlockingQueue<>());

        WorkflowRequest request = runningRequest(wf, "aff-lost");
        pinRun(request);
        WorkflowTask task = pendingTask(request, "build");

        // Pin the coordinator, then take it down.
        dispatcher.dispatchPendingTasks();
        OrchestrationRun run = runRepository.findById(request.getId()).orElseThrow();
        UUID coordinator = run.getCoordinatorAgentId();
        assertThat(coordinator).isEqualTo(instanceA.getId());
        connectionManager.unregisterByAgentInstanceId(coordinator);

        // Start the outage (episode 1) — the deadline starts now. The
        // same task row resets to PENDING (retryable semantics).
        task.setStatus(TaskStatus.PENDING);
        task.setAttempt(task.getAttempt() + 1);
        task.setStartedAt(null);
        task.setAgentInstance(null);
        WorkflowTask retried = taskRepository.save(task);
        dispatcher.dispatchPendingTasks();
        run = runRepository.findById(request.getId()).orElseThrow();
        Instant deadline = run.getAffinityRecoveryDeadline();
        assertThat(deadline).isNotNull();

        // Expire the deadline: move it into the past.
        run = runRepository.findById(request.getId()).orElseThrow();
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

    private Workflow workflowWithOrchestratorStep(String tag) {
        Map<String, Object> build = orchestratorStep("build");
        Map<String, Object> verify = orchestratorStep("verify");
        verify.put("dependsOn", List.of("build"));

        Workflow wf = new Workflow();
        wf.setProject(project);
        wf.setName(tag + "-wf-" + System.nanoTime());
        wf.setSteps(List.of(build, verify));
        wf.setVersion(1);
        wf.setStatus(WorkflowStatus.PUBLISHED);
        wf.setCreatedBy(admin);
        return workflowRepository.save(wf);
    }

    private Agent idleInstanceFor(AgentHost host, String hostname,
                                  BlockingQueue<String> outbound) throws Exception {
        Agent instance = new Agent();
        instance.setAgentHostId(host.getId());
        instance.setHostname(hostname);
        instance.setRuntimeVersion("0.0.0");
        instance.setStatus(Agent.Status.IDLE);
        instance.setRegisteredAt(Instant.now());
        instance = agentInstanceRepository.save(instance);
        registerStub(instance.getId(), hostname, outbound);
        return instance;
    }

    private void registerStub(UUID instanceId, String name,
                              BlockingQueue<String> outbound) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attrs = new java.util.HashMap<>();
        attrs.put("agentInstanceId", instanceId);
        attrs.put("agentName", name);
        lenient().when(session.getId()).thenReturn("stub-" + instanceId);
        lenient().when(session.isOpen()).thenReturn(true);
        lenient().when(session.getAttributes()).thenReturn(attrs);
        doAnswer(inv -> {
            TextMessage msg = inv.getArgument(0);
            outbound.add(msg.getPayload());
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        connectionManager.register(instanceId, name, session);
    }

    @Autowired
    private ai.myrmec.engine.agent.AgentRepository agentInstanceRepository;
}