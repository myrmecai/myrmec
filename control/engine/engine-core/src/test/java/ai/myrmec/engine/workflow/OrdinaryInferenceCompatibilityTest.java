// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.user.User;
import com.fasterxml.jackson.databind.JsonNode;
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
 * Design §19.3 "ordinary inference compatibility": after the Feature-10
 * orchestration pipeline, an INFERENCE workflow task still dispatches as
 * ordinary inference — no assignment block, no run rows, no dispatch rows, no
 * coordinator pinning — while the same dispatcher pass routes ORCHESTRATOR
 * steps through the orchestration shape. The two families coexist on one
 * dispatcher.
 *
 * <p>Both families now ride the same unified host-control wire
 * ({@code session.offer} → {@code session.open} → {@code execution.start}); the
 * ordinary/orchestration discriminator is the execution's engine-authored
 * {@code dispatchId} plus the presence of an assignment, exactly as the
 * terminal bridge reads it.</p>
 */
class OrdinaryInferenceCompatibilityTest extends WorkflowDispatchSupport {

    @Autowired private TaskDispatcherService dispatcher;
    @Autowired private OrchestrationRunRepository runRepository;
    @Autowired private OrchestrationDispatchRepository dispatchRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRequestRepository requestRepository;
    @Autowired private WorkflowTaskRepository taskRepository;
    @Autowired private TaskAttemptRepository attemptRepository;

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
    @DisplayName("an INFERENCE task dispatches session.offer → session.open → execution.start with NO assignment, run rows or dispatch rows")
    void ordinaryInferenceDispatchUnchanged() throws Exception {
        SocketHost host = openHost("compat-host", profile, project, 4);

        Workflow wf = plainInferenceWorkflow("compat");
        WorkflowRequest request = runningRequest(wf, "compat");
        WorkflowTask task = pendingTask(request);

        dispatcher.dispatchPendingTasks();

        // §7.1: the offer went out; nothing was assigned directly to a worker.
        UUID sessionId = offeredSessionId(host);

        // session.open first (§7.3) — the assembled §6.1 context, no assignment.
        acceptSession(host, sessionId);
        JsonNode sessionOpen = awaitFrame(host.outbound(), "session.open");
        assertThat(sessionOpen.path("payload").path("serviceType").asText()).isEqualTo("WORKFLOW");
        JsonNode orchestration = sessionOpen.path("payload").path("orchestration");
        assertThat(orchestration.isMissingNode() || orchestration.isNull())
                .as("ordinary inference never installs an orchestration assignment "
                        + "(the §16.3 discriminator is the execution's dispatchId)")
                .isTrue();

        // §7.4/§8.1: opened → the §8.1 execution.start carries the transcript.
        openSession(host, sessionId);
        JsonNode start = awaitFrame(host.outbound(), "execution.start");
        JsonNode startPayload = start.path("payload");
        assertThat(startPayload.path("input").path("messages").isArray()).isTrue();
        assertThat(startPayload.path("output").path("stream").asBoolean())
                .as("workflow steps are single-shot — no streaming")
                .isFalse();

        // Task RUNNING on the ordinary path; attempt bound to the serving worker.
        WorkflowTask stored = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TaskStatus.RUNNING);
        assertThat(stored.getAgentInstance()).isNotNull();
        assertThat(attemptRepository.findByTaskId(task.getId())).isNotEmpty();

        // The ordinary path creates no orchestration artifacts at all.
        assertThat(runRepository.findById(request.getId()))
                .as("an ordinary inference request never pins a run row")
                .isEmpty();
        assertThat(dispatchRepository.findAll())
                .as("an ordinary inference step records no durable dispatch row")
                .isEmpty();
        UUID executionId = UUID.fromString(start.path("executionId").asText());
        assertThat(sessionExecutionRepository.findById(executionId).orElseThrow()
                .getDispatchId())
                .as("no engine-authored dispatch identity on an ordinary execution")
                .isNull();
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
}
