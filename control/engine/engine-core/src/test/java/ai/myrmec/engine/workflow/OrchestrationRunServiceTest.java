// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentProfileVersionRepository;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature 10 (design §16.1/§16.2): run pinning at request creation and
 * the §7 step projection — the assignment's selected step must satisfy
 * the Agent's strict runtime schema (no engine-only fields; the
 * agentProfileCode alias at step level; retryPolicy projected).
 */
@DisplayName("F10: run pinning and §7 step projection")
class OrchestrationRunServiceTest extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private OrchestrationRunService orchestrationRunService;

    @Autowired
    private OrchestrationRunRepository runRepository;

    @Autowired
    private OrchestrationAssignmentAssembler assembler;

    @Autowired
    private WorkflowRepository workflowRepository;
    @Autowired
    private WorkflowTaskRepository taskRepository;
    @Autowired
    private WorkflowRequestRepository requestRepository;
    @Autowired
    private TaskAttemptRepository attemptRepository;
    @Autowired
    private AgentProfileVersionRepository agentProfileVersionRepository;

    @Test
    @DisplayName("pinRun persists the run row with the published version + content digest")
    void pinRunPersistsPublishedPin() {
        var project = data.project().named("pin-proj").create();
        var profile = data.agentProfile().named("pin-profile").create();
        var admin = userRepository.findById(TEST_ADMIN_ID).orElseThrow();

        Workflow wf = new Workflow();
        wf.setProject(project);
        wf.setName("pin-wf-" + System.nanoTime());
        wf.setSteps(List.of());
        wf.setVersion(1);
        wf.setStatus(WorkflowStatus.PUBLISHED);
        wf.setCreatedBy(admin);
        wf = workflowRepository.save(wf);

        WorkflowRequest req = new WorkflowRequest();
        req.setWorkflow(wf);
        req.setWorkflowVersion(1);
        req.setInput(Map.of());
        req.setStatus(RequestStatus.PENDING);
        req.setBranch("myrmec/pin");
        req.setCreatedBy(admin);
        req = requestRepository.save(req);

        var pinned = orchestrationRunService.pinRun(
                req.getId(), wf.getId(), project.getId(), profile.getId());

        // The pinned version is the currently published one.
        assertThat(pinned.getId()).isEqualTo(publishedVersionIdOf(profile.getId()));

        var run = runRepository.findById(req.getId()).orElseThrow();
        assertThat(run.getProfileVersionId()).isEqualTo(pinned.getId());
        assertThat(run.getProfileVersionDigest())
                .isEqualTo(OrchestrationRunService.contentDigestOf(pinned));
        assertThat(run.getWorkflowId()).isEqualTo(wf.getId());
        assertThat(run.getProjectId()).isEqualTo(project.getId());

        // pinnedVersionOf resolves the immutable row through the FK pin.
        var resolved = orchestrationRunService.pinnedVersionOf(req.getId());
        assertThat(resolved.getId()).isEqualTo(pinned.getId());
    }

    @Test
    @DisplayName("the assembled assignment's step matches the §7 authoring shape")
    void assembledStepMatchesRuntimeSchema() throws Exception {
        // The resolver performs a REAL git ls-remote (§16.2 step 4) —
        // seed a real local bare origin with a main branch.
        java.nio.file.Path origin = java.nio.file.Files.createTempDirectory("proj-origin-");
        new ProcessBuilder("git", "init", "--bare", "-b", "main",
                origin.toString()).inheritIO().start().waitFor();
        java.nio.file.Path seed = java.nio.file.Files.createTempDirectory("proj-seed-");
        git(seed, "init", "-b", "main");
        git(seed, "-c", "user.email=t@t", "-c", "user.name=t",
                "commit", "--allow-empty", "-m", "seed");
        String originUrl = origin.toAbsolutePath().toString().replace('\\', '/');
        git(seed, "push", originUrl, "main");

        var project = data.project().named("proj-proj")
                .withRepo(originUrl, "main").create();
        var profile = data.agentProfile().named("proj-profile").create();
        var admin = userRepository.findById(TEST_ADMIN_ID).orElseThrow();

        // A stored step exactly as Jackson persists WorkflowStepDto — with
        // the engine-local alias nested INSIDE the orchestration map.
        Map<String, Object> orch = completeOrchestration("primary");
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
        step.put("orchestration", orch);
        step.put("retryPolicy", Map.of(
                "maxRetries", 1, "initialBackoffSeconds", 2, "maxBackoffSeconds", 30));

        Workflow wf = new Workflow();
        wf.setProject(project);
        wf.setName("proj-wf-" + System.nanoTime());
        wf.setSteps(List.of(step));
        wf.setVersion(1);
        wf.setStatus(WorkflowStatus.PUBLISHED);
        wf.setCreatedBy(admin);
        wf = workflowRepository.save(wf);

        WorkflowRequest req = new WorkflowRequest();
        req.setWorkflow(wf);
        req.setWorkflowVersion(1);
        req.setInput(Map.of());
        req.setStatus(RequestStatus.RUNNING);
        req.setBranch("myrmec/proj");
        req.setCreatedBy(admin);
        req = requestRepository.save(req);

        WorkflowTask task = new WorkflowTask();
        task.setRequest(req);
        task.setStepId("build");
        task.setAgentProfile(profile);
        task.setInput(Map.of());
        task.setStatus(TaskStatus.PENDING);
        task.setAttempt(1);
        task.setMaxRetries(1);
        task = taskRepository.save(task);

        TaskAttempt attempt = task.createAttempt(null);
        attempt = attemptRepository.save(attempt);

        var version = agentProfileVersionRepository
                .findById(publishedVersionIdOf(profile.getId())).orElseThrow();
        var assembled = assembler.assemble(task, attempt, version, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> projected = (Map<String, Object>) assembled.assignment().get("step");

        // §7 OrchestrationStepAuthoring — EXACTLY these keys.
        assertThat(projected).containsOnlyKeys(
                "id", "name", "taskType", "agentProfileCode", "dependsOn",
                "retryPolicy", "orchestration");
        assertThat(projected.get("id")).isEqualTo("build");
        assertThat(projected.get("taskType")).isEqualTo("ORCHESTRATOR");
        // The alias moved from the orchestration map to step level.
        assertThat(projected.get("agentProfileCode")).isEqualTo("primary");

        @SuppressWarnings("unchecked")
        Map<String, Object> projectedOrch =
                (Map<String, Object>) projected.get("orchestration");
        assertThat(projectedOrch).doesNotContainKey("agentProfileCode");
        assertThat(projectedOrch).doesNotContainKey("dependsOn");
        assertThat(projectedOrch).containsKeys(
                "modelCode", "goal", "sourceSubPath", "workers",
                "checkpointStrategy", "completionCriteria", "budget");

        // retryPolicy projected to the three schema fields.
        @SuppressWarnings("unchecked")
        Map<String, Object> retry = (Map<String, Object>) projected.get("retryPolicy");
        assertThat(retry).containsOnlyKeys(
                "maxRetries", "initialBackoffSeconds", "maxBackoffSeconds");
        assertThat(retry.get("maxRetries")).isEqualTo(1);

        // No engine-only fields cross the wire.
        assertThat(assembled.canonicalJson()).doesNotContain("agentProfileId");
        assertThat(assembled.canonicalJson()).doesNotContain("pauseMode");
        assertThat(assembled.canonicalJson()).doesNotContain("transitions");
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

    private Map<String, Object> completeOrchestration(String profileCode) {
        Map<String, Object> orch = new LinkedHashMap<>();
        orch.put("agentProfileCode", profileCode);
        orch.put("modelCode", TEST_MODEL_CODE);
        orch.put("goal", "write a module");
        orch.put("specPath", null);
        orch.put("sourceSubPath", ".");
        Map<String, Object> worker = new LinkedHashMap<>();
        worker.put("name", "coder");
        worker.put("modelCode", TEST_MODEL_CODE);
        worker.put("capability", "implementation");
        worker.put("allowedTools", List.of("write_file"));
        worker.put("allowedCommands", List.of());
        orch.put("workers", List.of(worker));
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
        return orch;
    }

    private UUID publishedVersionIdOf(UUID profileId) {
        var version = agentProfileVersionService.findPublished(profileId).orElseThrow();
        return version.getId();
    }

    @Autowired
    private ai.myrmec.engine.agent.AgentProfileVersionService agentProfileVersionService;
}