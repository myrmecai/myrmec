// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.workflow.dto.CreateWorkflowRequest;
import ai.myrmec.engine.workflow.dto.WorkflowStepDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Feature 10 (design §16.2): the assembled assignment is complete and
 * self-contained — the full DispatchIdentity tuple, non-secret models
 * with credential references, resolved source, the ExecutionPolicy block
 * from the pinned Profile version with referenced-subset templates, and
 * exactly one selected step. Secrets never appear in the serialized bytes.
 */
@DisplayName("F10: OrchestrationAssignmentAssembler (§16.2)")
class OrchestrationAssignmentAssemblerTest extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private OrchestrationAssignmentAssembler assembler;

    @Autowired
    private WorkflowService workflowService;

    private UUID profileId;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        projectId = data.project().named("orch-asm")
                .withRepo("https://example.com/repo.git", "main").create().getId();
        profileId = data.agentProfile().named("orch-asm-profile").create().getId();
    }

    private Map<String, Object> orchestrationMap() {
        Map<String, Object> orch = new LinkedHashMap<>();
        orch.put("agentProfileCode", "primary");
        orch.put("modelCode", "github-gpt-4o");
        orch.put("goal", "write a module");
        orch.put("sourceSubPath", ".");
        Map<String, Object> worker = new LinkedHashMap<>();
        worker.put("name", "coder");
        worker.put("modelCode", "github-gpt-4o");
        worker.put("capability", "implementation");
        worker.put("allowedTools", List.of("write_file"));
        worker.put("allowedCommands", List.of());
        Map<String, Object> verifier = new LinkedHashMap<>();
        verifier.put("name", "verifier");
        verifier.put("modelCode", "github-gpt-4o");
        verifier.put("capability", "verification");
        verifier.put("allowedTools", List.of("read_file"));
        verifier.put("allowedCommands", List.of());
        orch.put("workers", List.of(worker, verifier));
        orch.put("checkpointStrategy", Map.of(
                "mode", "ON_VERIFICATION_PASS", "commitMessage", "checkpoint",
                "pushToRemote", false, "allowNoChanges", true));
        orch.put("completionCriteria", Map.of(
                "definitionOfDone", "compiles",
                "requireVerificationBy", List.of("verifier")));
        orch.put("budget", Map.of(
                "maxTokens", 50000, "maxWorkerCalls", 10,
                "maxVerifierRejectionsPerAttempt", 3,
                "maxOrchestratorIterations", 5, "maxWorkerIterations", 5,
                "onBudgetExceeded", "FAIL"));
        return orch;
    }

    private Workflow createWorkflow(Map<String, Object> orchestration) {
        WorkflowStepDto step = new WorkflowStepDto(
                "build", "Build", profileId, "Orchestrate",
                List.of(), Map.of(), 600, null, null,
                "ORCHESTRATOR", orchestration, null);
        var created = workflowService.create(new CreateWorkflowRequest(
                projectId, "asm-workflow-" + System.nanoTime(),
                "Assembler test", List.of(step), null, null), TEST_ADMIN_ID);
        return workflowRepository.findById(created.id()).orElseThrow();
    }

    @Test
    @DisplayName("assignment carries the complete dispatch identity and source")
    void assignmentCarriesCompleteIdentity() {
        Workflow workflow = createWorkflow(orchestrationMap());
        WorkflowRequest request = requestOf(workflow);
        WorkflowTask task = taskOf(workflow, request, "build");
        TaskAttempt attempt = attemptOf(task);
        var version = publishedVersion();

        var assembled = assembler.assemble(task, attempt, version, null);

        Map<String, Object> dispatch = (Map<String, Object>) assembled.assignment().get("dispatch");
        assertThat(dispatch.get("workflowId")).isEqualTo(workflow.getId().toString());
        assertThat(dispatch.get("runId")).isEqualTo(request.getId().toString());
        assertThat(dispatch.get("stepId")).isEqualTo("build");
        assertThat(dispatch.get("taskId")).isEqualTo(task.getId().toString());
        assertThat(dispatch.get("attemptId")).isEqualTo(attempt.getId().toString());
        assertThat(dispatch.get("attemptOrdinal")).isEqualTo(1);
        // §16.2: dispatchId == the attempt UUID in V1
        assertThat(dispatch.get("dispatchId")).isEqualTo(attempt.getId().toString());
        assertThat(dispatch).doesNotContainKey("continuationId");

        Map<String, Object> source = (Map<String, Object>) assembled.assignment().get("source");
        assertThat(source).containsKeys("repoUrl", "sourceBranch", "sourceBaseCommit", "targetBranch");
        assertThat(String.valueOf(source.get("targetBranch"))).isEqualTo(request.getBranch());
    }

    @Test
    @DisplayName("models are non-secret with credential references, no api keys")
    void modelsAreNonSecret() {
        Workflow workflow = createWorkflow(orchestrationMap());
        WorkflowRequest request = requestOf(workflow);
        WorkflowTask task = taskOf(workflow, request, "build");
        TaskAttempt attempt = attemptOf(task);

        var assembled = assembler.assemble(task, attempt, publishedVersion(), null);

        List<Map<String, Object>> models =
                (List<Map<String, Object>>) assembled.assignment().get("models");
        assertThat(models).isNotEmpty();
        for (Map<String, Object> model : models) {
            assertThat(model).containsKey("credentialRef");
            assertThat(model).doesNotContainKey("apiKey");
            assertThat(String.valueOf(model.get("credentialRef")))
                    .startsWith("model:");
        }
        // no raw secret material anywhere in the serialized bytes
        assertThat(assembled.canonicalJson()).doesNotContain("apiKey");
        assertThat(assembled.canonicalJson()).doesNotContain("password");
    }

    @Test
    @DisplayName("policy is assembled from the pinned version with fail-safe git policy")
    void policyFromPinnedVersion() {
        Workflow workflow = createWorkflow(orchestrationMap());
        WorkflowRequest request = requestOf(workflow);
        WorkflowTask task = taskOf(workflow, request, "build");
        TaskAttempt attempt = attemptOf(task);

        var assembled = assembler.assemble(task, attempt, publishedVersion(), null);

        Map<String, Object> policy = (Map<String, Object>) assembled.assignment().get("policy");
        assertThat(policy).containsKeys(
                "allowedTools", "commandTemplates", "requiredIsolation",
                "approvalPolicy", "gitPolicy", "workspaceRetentionSeconds");
        // §16.1 fail-safe git policy: checkpoint allowed, push denied
        Map<String, Object> gitPolicy = (Map<String, Object>) policy.get("gitPolicy");
        assertThat(gitPolicy.get("allowCheckpoint")).isEqualTo(true);
        assertThat(gitPolicy.get("allowPush")).isEqualTo(false);
    }

    @Test
    @DisplayName("exactly one selected step ships, with its orchestration map")
    void oneSelectedStep() {
        Workflow workflow = createWorkflow(orchestrationMap());
        WorkflowRequest request = requestOf(workflow);
        WorkflowTask task = taskOf(workflow, request, "build");
        TaskAttempt attempt = attemptOf(task);

        var assembled = assembler.assemble(task, attempt, publishedVersion(), null);

        Map<String, Object> step = (Map<String, Object>) assembled.assignment().get("step");
        assertThat(step.get("id")).isEqualTo("build");
        assertThat(step.get("taskType")).isEqualTo("ORCHESTRATOR");
        assertThat(step.get("orchestration")).isNotNull();
        assertThat(assembled.assignment()).doesNotContainKey("steps");
    }

    @Test
    @DisplayName("continuation directive is included only when supplied")
    void continuationDirectiveOptional() {
        Workflow workflow = createWorkflow(orchestrationMap());
        WorkflowRequest request = requestOf(workflow);
        WorkflowTask task = taskOf(workflow, request, "build");
        TaskAttempt attempt = attemptOf(task);

        var fresh = assembler.assemble(task, attempt, publishedVersion(), null);
        assertThat(fresh.assignment()).doesNotContainKey("continuation");

        var resumed = assembler.assemble(task, attempt, publishedVersion(),
                new OrchestrationAssignmentAssembler.ContinuationDirective("cont-1", "prev-1"));
        Map<String, Object> cont = (Map<String, Object>) resumed.assignment().get("continuation");
        assertThat(cont.get("continuationId")).isEqualTo("cont-1");
        assertThat(cont.get("previousDispatchId")).isEqualTo("prev-1");
        assertThat(String.valueOf(((Map<String, Object>) resumed.assignment()
                .get("dispatch")).get("continuationId"))).isEqualTo("cont-1");
    }

    @Test
    @DisplayName("canonical digest is sha-256 over the serialized bytes")
    void canonicalDigest() {
        Workflow workflow = createWorkflow(orchestrationMap());
        WorkflowRequest request = requestOf(workflow);
        WorkflowTask task = taskOf(workflow, request, "build");
        TaskAttempt attempt = attemptOf(task);

        var assembled = assembler.assemble(task, attempt, publishedVersion(), null);
        assertThat(assembled.assignmentDigest()).hasSize(64);
        assertThat(assembled.assignmentDigest()).isEqualTo(
                ai.myrmec.engine.workflow.OrchestrationIds.sha256Hex(
                        assembled.canonicalJson().getBytes()));
    }

    @Test
    @DisplayName("non-orchestrator step is rejected")
    void nonOrchestratorStepRejected() {
        WorkflowStepDto step = new WorkflowStepDto(
                "plain", "Plain", profileId, null,
                List.of(), Map.of(), 300, null, null,
                "INFERENCE", null, null);
        var created = workflowService.create(new CreateWorkflowRequest(
                projectId, "asm-plain-" + System.nanoTime(), "Plain",
                List.of(step), null, null), TEST_ADMIN_ID);
        Workflow workflow = workflowRepository().findById(created.id()).orElseThrow();
        WorkflowRequest request = requestOf(workflow);
        WorkflowTask task = taskOf(workflow, request, "plain");
        TaskAttempt attempt = attemptOf(task);

        assertThatThrownBy(() -> assembler.assemble(task, attempt, publishedVersion(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not an ORCHESTRATOR step");
    }

    // ── helpers ──────────────────────────────────────────────


    @Autowired
    private ai.myrmec.engine.workflow.WorkflowRepository workflowRepository;

    @Autowired
    private ai.myrmec.engine.workflow.WorkflowRequestRepository requestRepository;

    @Autowired
    private ai.myrmec.engine.workflow.WorkflowTaskRepository taskRepository;

    @Autowired
    private ai.myrmec.engine.workflow.TaskAttemptRepository attemptRepository;

    @Autowired
    private ai.myrmec.engine.agent.AgentProfileVersionService versionService;

    private WorkflowRepository workflowRepository() {
        return workflowRepository;
    }

    private ai.myrmec.engine.agent.AgentProfileVersion publishedVersion() {
        return versionService.requirePublished(profileId);
    }

    @Autowired
    private ai.myrmec.engine.user.UserRepository userRepository;

    private WorkflowRequest requestOf(Workflow workflow) {
        WorkflowRequest request = new WorkflowRequest();
        request.setWorkflow(workflow);
        request.setWorkflowVersion(workflow.getVersion());
        request.setInput(Map.of("featureName", "assembler"));
        request.setStatus(ai.myrmec.engine.workflow.RequestStatus.PENDING);
        request.setBranch("myrmec/" + UUID.randomUUID().toString().substring(0, 8));
        request.setCreatedBy(userRepository.findById(TEST_ADMIN_ID).orElseThrow());
        request.setCreatedAt(java.time.Instant.now());
        return requestRepository.save(request);
    }

    private WorkflowTask taskOf(Workflow workflow, WorkflowRequest request, String stepId) {
        WorkflowTask task = new WorkflowTask();
        task.setRequest(request);
        task.setStepId(stepId);
        task.setInput(Map.of("prompt", "go"));
        task.setStatus(TaskStatus.PENDING);
        task.setAttempt(1);
        // the profile relation is required by the entity contract
        ai.myrmec.engine.agent.AgentProfile profile =
                agentProfileRepository.findById(profileId).orElseThrow();
        task.setAgentProfile(profile);
        return taskRepository.save(task);
    }

    @Autowired
    private ai.myrmec.engine.agent.AgentProfileRepository agentProfileRepository;

    private ai.myrmec.engine.agent.AgentProfileRepository agentProfileRepository() {
        return agentProfileRepository;
    }

    private TaskAttempt attemptOf(WorkflowTask task) {
        TaskAttempt attempt = task.createAttempt(null);
        return attemptRepository.save(attempt);
    }
}