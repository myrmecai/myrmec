// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.user.User;
import ai.myrmec.engine.websocket.AgentConnectionManager;
import ai.myrmec.engine.websocket.AgentWebSocketHandler;
import ai.myrmec.engine.websocket.InboundOrchestrationHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
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
 * Design §16.4 (6) respawn/restart recovery: a crashed/restarted worker
 * reconnects, the engine retransmits the unaccepted dispatch's exact
 * stored bytes, and re-admission is idempotent — the same attempt
 * resumes, no new attempt, duplicate results replay the stored outcome.
 */
class OrchestrationRespawnRecoveryTest extends IntegrationTestBase {

    @Autowired private TestDataBuilder data;
    @Autowired private TaskDispatcherService dispatcher;
    @Autowired private OrchestrationRunService runService;
    @Autowired private OrchestrationRunRepository runRepository;
    @Autowired private OrchestrationDispatchRepository dispatchRepository;
    @Autowired private OrchestrationDispatchRelay dispatchRelay;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRequestRepository requestRepository;
    @Autowired private WorkflowTaskRepository taskRepository;
    @Autowired private TaskAttemptRepository attemptRepository;
    @Autowired private AgentConnectionManager connectionManager;
    @Autowired private AgentWebSocketHandler agentWebSocketHandler;
    @Autowired private InboundOrchestrationHandler inboundHandler;
    @Autowired private ObjectMapper objectMapper;

    private Project project;
    private AgentProfile profile;
    private User admin;

    @BeforeEach
    void setUp() throws Exception {
        java.nio.file.Path origin = java.nio.file.Files.createTempDirectory("resp-origin-");
        origin = origin.resolve("origin.git");
        git(origin.getParent(), "init", "--bare", "-b", "main", origin.toString());
        java.nio.file.Path seed = java.nio.file.Files.createTempDirectory("resp-seed-");
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
    @DisplayName("worker crash mid-dispatch: reconnect retransmits the exact stored bytes; re-admission is idempotent; no new attempt")
    void respawnRetransmitsAndReadmitsIdempotently() throws Exception {
        // One host + one idle instance.
        AgentHost host = data.agent().named("resp-host").withProfile(profile)
                .inProject(project).create().agent();
        BlockingQueue<String> outbound = new LinkedBlockingQueue<>();
        Agent instance = idleInstanceFor(host, "resp-agent", outbound);

        // Request + run + PENDING orchestrator task.
        Workflow wf = workflowWithOrchestratorStep();
        WorkflowRequest request = runningRequest(wf, "resp");
        runService.pinRun(request.getId(), wf.getId(), project.getId(), profile.getId());
        WorkflowTask task = pendingTask(request);

        // Dispatch #1: the frame goes out on the wire...
        dispatcher.dispatchPendingTasks();
        TaskAttempt attempt = attemptRepository
                .findByTaskIdOrderByAttemptNumberAsc(task.getId()).get(0);
        OrchestrationDispatch dispatch = dispatchRepository
                .findById(attempt.getId()).orElseThrow();
        String firstFrame = outbound.poll(3, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(firstFrame).as("the dispatch frame reached the wire").isNotNull();

        // ...the worker CRASHES before inference.accept. The frame was
        // SENT but never ACCEPTED; the attempt stays RUNNING.
        assertThat(dispatch.getDeliveryState())
                .as("sent on the wire, never accepted — the crash-before-ack case")
                .isEqualTo("SENT");
        connectionManager.unregisterByAgentInstanceId(instance.getId());
        assertThat(connectionManager.isConnected(instance.getId())).isFalse();

        // Respawn: the same logical instance reconnects.
        BlockingQueue<String> respawnOutbound = new LinkedBlockingQueue<>();
        WebSocketSession respawn = mock(WebSocketSession.class);
        Map<String, Object> attrs = new java.util.HashMap<>();
        attrs.put("agentInstanceId", instance.getId());
        attrs.put("agentName", "resp-agent");
        lenient().when(respawn.getId()).thenReturn("respawn-" + instance.getId());
        lenient().when(respawn.isOpen()).thenReturn(true);
        lenient().when(respawn.getAttributes()).thenReturn(attrs);
        doAnswer(inv -> {
            TextMessage msg = inv.getArgument(0);
            respawnOutbound.add(msg.getPayload());
            return null;
        }).when(respawn).sendMessage(any(TextMessage.class));
        agentWebSocketHandler.afterConnectionEstablished(respawn);

        // The engine retransmitted the EXACT stored bytes (§16.4 (6)) —
        // same canonical assignment, same digest, same attempt.
        String retransmitted = respawnOutbound.poll(3, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(retransmitted).as("reconnect retransmits the unaccepted dispatch").isNotNull();
        assertThat(extractOrchestrationAssignment(retransmitted).toString())
                .isEqualTo(extractOrchestrationAssignment(firstFrame).toString());
        assertThat(attemptRepository.findByTaskIdOrderByAttemptNumberAsc(task.getId()))
                .as("respawn never creates a second attempt — the same one resumes")
                .hasSize(1);

        // The respawned worker re-admits: inference.accept (a replay of the
        // pre-crash admission if it happened, or a fresh one) — idempotent.
        boolean admitted = dispatchRelay.accept(attempt.getId(), dispatch.getAssignmentDigest());
        assertThat(admitted).isTrue();
        boolean replay = dispatchRelay.accept(attempt.getId(), dispatch.getAssignmentDigest());
        assertThat(replay).as("duplicate accept replays the stored acknowledgement").isTrue();
        assertThat(dispatchRepository.findById(attempt.getId()).orElseThrow()
                .getDeliveryState()).isEqualTo("ACCEPTED");
        assertThat(attemptRepository.findByTaskIdOrderByAttemptNumberAsc(task.getId()))
                .hasSize(1);

        // A duplicate terminal result for the resumed dispatch replays
        // the stored outcome — the engine never applies a second state
        // change (§7.3/§16.3 idempotence across the restart).
        UUID resultId = UUID.randomUUID();
        inboundHandler.onOrchestrationResult(instance.getId(),
                objectMapper.readTree(resultFrame(request.getId(), task.getId(),
                        attempt.getId(), resultId, "COMPLETED", "respawned")));
        // Wait for the outcome to apply.
        AwaitilityHelper.awaitStatus(attemptRepository, attempt.getId(),
                AttemptStatus.COMPLETED);
        inboundHandler.onOrchestrationResult(instance.getId(),
                objectMapper.readTree(resultFrame(request.getId(), task.getId(),
                        attempt.getId(), resultId, "COMPLETED", "respawned")));
        assertThat(attemptRepository.findById(attempt.getId()).orElseThrow()
                .getOrchestrationResultId()).isEqualTo(resultId);
        assertThat(attemptRepository.findByTaskIdOrderByAttemptNumberAsc(task.getId()))
                .as("a duplicate result never spawns another attempt")
                .hasSize(1);
    }

    // ── frame helpers ──────────────────────────────────────────

    /** The orchestration assignment block of an inference.assign frame. */
    private JsonNode extractOrchestrationAssignment(String frame) throws Exception {
        JsonNode json = objectMapper.readTree(frame);
        return json.path("payload").path("orchestration");
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

    private Agent idleInstanceFor(AgentHost host, String hostname,
                                  BlockingQueue<String> outbound) throws Exception {
        Agent instance = new Agent();
        instance.setAgentHostId(host.getId());
        instance.setHostname(hostname);
        instance.setRuntimeVersion("0.0.0");
        instance.setStatus(Agent.Status.IDLE);
        instance.setRegisteredAt(Instant.now());
        instance = agentInstanceRepository.save(instance);
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attrs = new java.util.HashMap<>();
        attrs.put("agentInstanceId", instance.getId());
        attrs.put("agentName", hostname);
        lenient().when(session.getId()).thenReturn("stub-" + instance.getId());
        lenient().when(session.isOpen()).thenReturn(true);
        lenient().when(session.getAttributes()).thenReturn(attrs);
        doAnswer(inv -> {
            TextMessage msg = inv.getArgument(0);
            outbound.add(msg.getPayload());
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        connectionManager.register(instance.getId(), hostname, session);
        return instance;
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

    @Autowired
    private ai.myrmec.engine.agent.AgentRepository agentInstanceRepository;

    /** Small Awaitility wrapper for post-handler status reads. */
    static final class AwaitilityHelper {
        static void awaitStatus(TaskAttemptRepository repo, UUID attemptId,
                                AttemptStatus expected) {
            org.awaitility.Awaitility.await()
                    .atMost(java.time.Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(
                            repo.findById(attemptId).orElseThrow().getStatus())
                            .isEqualTo(expected));
        }
    }
}