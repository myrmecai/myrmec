// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.workflow.dto.CreateWorkflowRequest;
import ai.myrmec.engine.workflow.dto.WorkflowStepDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature 10 (design §16.1): the JSON-through-TEXT round trip. A workflow
 * created through {@link WorkflowService} with an {@code ORCHESTRATOR} step
 * must reload with {@code taskType}, the complete nested {@code orchestration}
 * map, and the step-level {@code retryPolicy} intact — this test fails if
 * Jackson silently drops either new DTO field.
 */
@DisplayName("F10: workflow orchestration JSON round-trip (§16.1)")
class WorkflowOrchestrationRoundTripTest extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private WorkflowService workflowService;

    @Test
    @DisplayName("taskType, nested orchestration, and retryPolicy survive both Jackson directions")
    void orchestrationMapSurvivesRoundTrip() {
        Project project = data.project().named("orch-rt").create();
        UUID profileId = data.agentProfile().named("orch-rt-profile").create().getId();

        // A representative nested orchestration map — mirrors the §7 fields
        // the compiled DSL carries per step.
        Map<String, Object> orchestration = new HashMap<>();
        orchestration.put("agentProfileCode", "primary");
        orchestration.put("models", List.of(
                Map.of("role", "ORCHESTRATOR", "modelCode", "stub-orch"),
                Map.of("role", "WORKER", "name", "coder", "modelCode", "stub-worker")));
        orchestration.put("budget", Map.of("maxTokens", 50000, "onBudgetExceeded", "FAIL"));
        orchestration.put("steps", List.of(Map.of(
                "stepId", "backend",
                "workerName", "coder",
                "task", "write the module",
                "writeFiles", List.of("src/module.ts"))));
        Map<String, Object> retryPolicy = Map.of(
                "maxRetries", 2,
                "initialBackoffSeconds", 5,
                "maxBackoffSeconds", 30);

        WorkflowStepDto orchestratorStep = new WorkflowStepDto(
                "build", "Build the project", profileId,
                "Orchestrate the build", List.of(), Map.of(),
                600, null, null,
                "ORCHESTRATOR", orchestration, retryPolicy);

        WorkflowStepDto inferenceStep = new WorkflowStepDto(
                "review", "Review the result", profileId,
                "Review the generated code", List.of("build"), Map.of(),
                300, null, null,
                "INFERENCE", null, null);

        var created = workflowService.create(new CreateWorkflowRequest(
                project.getId(),
                "orch-rt-workflow-" + System.nanoTime(),
                "Orchestration round-trip",
                List.of(orchestratorStep, inferenceStep), null, null), TEST_ADMIN_ID);

        var reloaded = workflowService.findById(created.id());

        // taskType defaults correctly and round-trips
        var steps = reloaded.steps();
        assertThat(steps).hasSize(2);

        var reloadedOrch = steps.stream()
                .filter(s -> "build".equals(s.id())).findFirst().orElseThrow();
        assertThat(reloadedOrch.taskType()).isEqualTo("ORCHESTRATOR");
        assertThat(reloadedOrch.isOrchestrator()).isTrue();
        assertThat(reloadedOrch.orchestration()).isNotNull();
        assertThat(reloadedOrch.orchestration())
                .containsEntry("agentProfileCode", "primary")
                .containsEntry("budget", Map.of("maxTokens", 50000, "onBudgetExceeded", "FAIL"));
        assertThat(reloadedOrch.orchestration().get("models"))
                .asList()
                .hasSize(2);
        assertThat(reloadedOrch.retryPolicy())
                .containsEntry("maxRetries", 2)
                .containsEntry("initialBackoffSeconds", 5)
                .containsEntry("maxBackoffSeconds", 30);
        // retryPolicy.maxRetries seeds the legacy field
        assertThat(reloadedOrch.maxRetries()).isEqualTo(2);

        // INFERENCE step: absent orchestration stays null, default taskType
        var reloadedInf = steps.stream()
                .filter(s -> "review".equals(s.id())).findFirst().orElseThrow();
        assertThat(reloadedInf.taskType()).isEqualTo("INFERENCE");
        assertThat(reloadedInf.isOrchestrator()).isFalse();
        assertThat(reloadedInf.orchestration()).isNull();
        // absent retryPolicy → legacy default 0
        assertThat(reloadedInf.maxRetries()).isZero();
    }

    @Test
    @DisplayName("retryPolicy.maxRetries wins over the legacy bare maxRetries")
    void retryPolicyWinsOverLegacyField() {
        Project project = data.project().named("orch-rt2").create();
        UUID profileId = data.agentProfile().named("orch-rt2-profile").create().getId();

        WorkflowStepDto step = new WorkflowStepDto(
                "legacy", "Legacy plus policy", profileId,
                null, List.of(), Map.of(),
                null, 7, null,
                "INFERENCE", null, Map.of("maxRetries", 3));

        var created = workflowService.create(new CreateWorkflowRequest(
                project.getId(),
                "orch-rt2-workflow-" + System.nanoTime(),
                "Retry policy precedence",
                List.of(step), null, null), TEST_ADMIN_ID);

        var reloaded = workflowService.findById(created.id()).steps().get(0);
        assertThat(reloaded.maxRetries()).isEqualTo(3);
    }
}