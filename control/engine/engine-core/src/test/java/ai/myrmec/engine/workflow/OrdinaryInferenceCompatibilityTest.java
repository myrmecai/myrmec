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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

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
 * Design §19.3 "ordinary inference compatibility": after the Feature-10
 * orchestration pipeline, an INFERENCE workflow task still dispatches
 * through the ordinary session.open + inference.assign path — no run
 * rows, no orchestration frames, no coordinator pinning — while the
 * same dispatcher pass routes ORCHESTRATOR steps through the new
 * pipeline. The two families coexist on one dispatcher.
 */
class OrdinaryInferenceCompatibilityTest extends IntegrationTestBase {

    @Autowired private TestDataBuilder data;
    @Autowired private TaskDispatcherService dispatcher;
    @Autowired private OrchestrationRunRepository runRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRequestRepository requestRepository;
    @Autowired private WorkflowTaskRepository taskRepository;
    @Autowired private TaskAttemptRepository attemptRepository;
    @Autowired private ObjectMapper objectMapper;

    private Project project;
    private AgentProfile profile;
    private User admin;

    @BeforeEach
    void setUp() {
        project = data.project().named("compat").withRepo("https://x.git", "main").create();
        profile = data.agentProfile().named("compat-profile").create();
        admin = userRepository.findById(TEST_ADMIN_ID).orElseThrow();
    }

    @Test
    @DisplayName("an INFERENCE task dispatches session.open + inference.assign with NO orchestration frames or run rows")
    void ordinaryInferenceDispatchUnchanged() throws Exception {
        AgentHost host = data.agent().named("compat-host").withProfile(profile)
                .inProject(project).create().agent();
        BlockingQueue<String> outbound = new LinkedBlockingQueue<>();
        Agent instance = idleInstanceFor(host, "compat-agent", outbound);

        // A plain INFERENCE workflow (no taskType, no orchestration map).
        Workflow wf = plainInferenceWorkflow("compat");
        WorkflowRequest request = runningRequest(wf, "compat");
        WorkflowTask task = pendingTask(request);

        dispatcher.dispatchPendingTasks();

        // The scheduled dispatcher pass may race the direct call (both found
        // the PENDING task; at-least-once is the §16.3 delivery discipline —
        // the orchestration pipeline deduplicates by digest). The ORDINARY
        // contract: session.open precedes inference.assign, NO assign ever
        // carries an orchestration block, and no run row is pinned.
        List<JsonNode> frames = new ArrayList<>();
        String raw;
        while ((raw = outbound.poll(1, java.util.concurrent.TimeUnit.SECONDS)) != null) {
            frames.add(objectMapper.readTree(raw));
        }
        assertThat(frames).isNotEmpty();
        assertThat(frames.get(0).path("type").asText())
                .as("session.open is the first frame on the wire")
                .isEqualTo("session.open");

        List<JsonNode> assigns = frames.stream()
                .filter(f -> "inference.assign".equals(f.path("type").asText()))
                .toList();
        assertThat(assigns).as("inference.assign follows session.open").isNotEmpty();
        for (JsonNode assign : assigns) {
            JsonNode orchestration = assign.path("payload").path("orchestration");
            assertThat(orchestration.isMissingNode() || orchestration.isNull())
                    .as("ordinary inference never carries an orchestration assignment "
                            + "(null/absent both mean ordinary — the discriminator is "
                            + "the payload's dispatchId, §16.3)")
                    .isTrue();
        }

        // Task RUNNING on the ordinary path; and NO orchestration run
        // rows exist for this request (no pinRun).
        WorkflowTask stored = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TaskStatus.RUNNING);
        assertThat(stored.getAgentInstance()).isNotNull();
        assertThat(attemptRepository.findByTaskId(task.getId())).isNotEmpty();
        assertThat(runRepository.findById(request.getId()))
                .as("an ordinary inference request never pins a run row")
                .isEmpty();
    }

    // ── fixtures ───────────────────────────────────────────────

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

    private WorkflowTask pendingTask(WorkflowRequest request) {
        WorkflowTask task = new WorkflowTask();
        task.setRequest(request);
        task.setStepId("analyze");
        task.setAgentProfile(profile);
        task.setInput(Map.of());
        task.setStatus(TaskStatus.PENDING);
        task.setAttempt(1);
        task.setMaxRetries(0);
        return taskRepository.save(task);
    }

    /** A stored step exactly as Jackson persists WorkflowStepDto — plain
     * INFERENCE: no taskType (defaults), no orchestration map. */
    private Workflow plainInferenceWorkflow(String tag) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", "analyze");
        step.put("name", "Analyze");
        step.put("agentProfileId", profile.getId().toString());
        step.put("prompt", "Summarize the repo");
        step.put("dependsOn", List.of());
        step.put("transitions", Map.of());
        step.put("timeoutSeconds", 300);
        step.put("maxRetries", 0);
        step.put("pauseMode", "NONE");

        Workflow wf = new Workflow();
        wf.setProject(project);
        wf.setName(tag + "-wf-" + System.nanoTime());
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

    @Autowired
    private ai.myrmec.engine.agent.AgentRepository agentInstanceRepository;
    @Autowired
    private ai.myrmec.engine.websocket.AgentConnectionManager connectionManager;
}