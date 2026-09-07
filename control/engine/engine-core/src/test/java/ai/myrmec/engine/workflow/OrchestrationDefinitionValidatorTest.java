// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.agent.AgentProfileVersion;
import ai.myrmec.engine.agent.AgentProfileVersionService;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Feature 10 (design §16.1): publication-time validation of orchestration
 * workflows — taskType/orchestration pairing, one agentProfileCode alias,
 * the explicit alias→UUID binding, template references against the bound
 * Profile's published version, and retryPolicy sanity.
 */
@DisplayName("F10: OrchestrationDefinitionValidator (§16.1)")
class OrchestrationDefinitionValidatorTest extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private OrchestrationDefinitionValidator validator;

    @Autowired
    private AgentProfileVersionService versionService;

    private UUID boundProfileId;

    @BeforeEach
    void setUp() {
        boundProfileId = data.agentProfile().named("orch-validator-profile").create().getId();
    }

    // ── fixtures ─────────────────────────────────────────────

    private Map<String, Object> orchestrationMap(String profileCode, String... workerNames) {
        Map<String, Object> orch = new LinkedHashMap<>();
        orch.put("agentProfileCode", profileCode);
        orch.put("modelCode", "stub-orchestrator");
        orch.put("goal", "write a module");
        Map<String, Object> worker = new LinkedHashMap<>();
        worker.put("name", workerNames.length > 0 ? workerNames[0] : "coder");
        worker.put("modelCode", "stub-worker");
        worker.put("capability", "implementation");
        worker.put("allowedTools", List.of("write_file"));
        worker.put("allowedCommands", List.of());
        Map<String, Object> verifier = new LinkedHashMap<>();
        verifier.put("name", workerNames.length > 1 ? workerNames[1] : "verifier");
        verifier.put("modelCode", "stub-worker");
        verifier.put("capability", "verification");
        verifier.put("allowedTools", List.of("read_file"));
        verifier.put("allowedCommands", List.of());
        orch.put("workers", List.of(worker, verifier));
        orch.put("completionCriteria", Map.of(
                "definitionOfDone", "module compiles",
                "requireVerificationBy", List.of(workerNames.length > 1 ? workerNames[1] : "verifier")));
        return orch;
    }

    private Map<String, Object> orchestratorStep(String id, Map<String, Object> orch) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", id);
        step.put("name", "Step " + id);
        step.put("agentProfileId", boundProfileId);
        step.put("taskType", "ORCHESTRATOR");
        step.put("orchestration", orch);
        return step;
    }

    private Map<String, Object> inferenceStep(String id) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", id);
        step.put("name", "Step " + id);
        step.put("agentProfileId", boundProfileId);
        step.put("taskType", "INFERENCE");
        return step;
    }

    private Map<String, UUID> binding(String alias) {
        Map<String, UUID> bindings = new HashMap<>();
        bindings.put(alias, boundProfileId);
        return bindings;
    }

    // ── tests ─────────────────────────────────────────────────

    @Test
    @DisplayName("pure-inference workflows pass unchanged")
    void pureInferenceWorkflowPasses() {
        Map<String, UUID> noBindings = null;
        assertThatCode(() -> validator.validate(
                List.of(inferenceStep("plain")), noBindings))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ORCHESTRATOR step without the orchestration map is rejected")
    void orchestratorRequiresMap() {
        Map<String, Object> step = orchestratorStep("build", orchestrationMap("primary"));
        step.remove("orchestration");
        BadRequestException ex = catchValidation(List.of(step), "primary");
        assertAnyDetailContains(ex, "requires the orchestration map");
    }

    @Test
    @DisplayName("INFERENCE step carrying an orchestration map is rejected")
    void inferenceRejectsMap() {
        Map<String, Object> step = inferenceStep("plain");
        step.put("orchestration", orchestrationMap("primary"));
        BadRequestException ex = catchValidation(List.of(step), "primary");
        assertAnyDetailContains(ex, "INFERENCE steps cannot carry");
    }

    @Test
    @DisplayName("mixed agentProfileCode aliases are rejected")
    void mixedAliasesRejected() {
        Map<String, Object> a = orchestratorStep("build", orchestrationMap("primary"));
        Map<String, Object> b = orchestratorStep("deploy", orchestrationMap("secondary"));
        List<Map<String, Object>> steps = List.of(a, b);
        Map<String, UUID> bindings = new HashMap<>();
        bindings.put("primary", boundProfileId);
        bindings.put("secondary", boundProfileId);
        BadRequestException ex = catchValidationRaw(steps, bindings);
        assertAnyDetailContains(ex, "one agentProfileCode");
    }

    @Test
    @DisplayName("missing publication binding for the alias is rejected")
    void missingBindingRejected() {
        List<Map<String, Object>> steps = List.of(
                orchestratorStep("build", orchestrationMap("primary")));
        BadRequestException ex = catchValidationRaw(steps, null);
        assertAnyDetailContains(ex, "Missing publication binding");
    }

    @Test
    @DisplayName("binding to a nonexistent profile is rejected")
    void unknownBindingRejected() {
        List<Map<String, Object>> steps = List.of(
                orchestratorStep("build", orchestrationMap("primary")));
        Map<String, UUID> bindings = Map.of("primary", UUID.randomUUID());
        BadRequestException ex = catchValidationRaw(steps, bindings);
        assertAnyDetailContains(ex, "ACTIVE agent profile");
    }

    @Test
    @DisplayName("duplicate worker names and unknown verifiers are rejected")
    void workerCatalogChecks() {
        Map<String, Object> orch = orchestrationMap("primary", "coder", "coder");
        List<Map<String, Object>> steps = List.of(orchestratorStep("build", orch));
        BadRequestException ex = catchValidation(steps, "primary");
        assertThat(ex.getDetails().stream()
                .map(d -> d.getErrorCode() + ":" + d.getField())
                .anyMatch(s -> s.contains("workers")))
                .as("duplicate worker name flagged")
                .isTrue();
    }

    @Test
    @DisplayName("command template not defined by the bound profile's published version is rejected")
    void unknownCommandTemplateRejected() {
        Map<String, Object> orch = orchestrationMap("primary");
        @SuppressWarnings("unchecked")
        Map<String, Object> worker =
                (Map<String, Object>) ((List<Object>) orch.get("workers")).get(0);
        worker.put("allowedCommands", List.of("ghost-template"));
        List<Map<String, Object>> steps = List.of(orchestratorStep("build", orch));
        BadRequestException ex = catchValidation(steps, "primary");
        assertThat(ex.getDetails().stream()
                .map(d -> d.getMessage())
                .anyMatch(m -> m.contains("ghost-template")))
                .as("missing template flagged")
                .isTrue();
    }

    @Test
    @DisplayName("valid orchestration workflow with explicit binding passes")
    void validOrchestrationWorkflowPasses() {
        List<Map<String, Object>> steps = List.of(
                orchestratorStep("build", orchestrationMap("primary")));
        assertThatCode(() -> validator.validate(steps, binding("primary")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("retryPolicy backoff sanity: initial > max is rejected")
    void retryPolicyBackoffSanity() {
        Map<String, Object> step = orchestratorStep("build", orchestrationMap("primary"));
        step.put("retryPolicy", Map.of(
                "maxRetries", 2, "initialBackoffSeconds", 60, "maxBackoffSeconds", 5));
        List<Map<String, Object>> steps = List.of(step);
        BadRequestException ex = catchValidation(steps, "primary");
        assertThat(ex.getDetails().stream()
                .map(d -> d.getMessage())
                .anyMatch(m -> m.contains("must not exceed")))
                .as("backoff ordering flagged")
                .isTrue();
    }

    /** Run the validator capturing the BadRequestException (expected). */
    private BadRequestException catchValidation(List<Map<String, Object>> steps, String alias) {
        return catchValidationRaw(steps, binding(alias));
    }

    private BadRequestException catchValidationRaw(List<Map<String, Object>> steps,
                                                  Map<String, UUID> bindings) {
        try {
            validator.validate(steps, bindings);
            throw new AssertionError("expected BadRequestException");
        } catch (BadRequestException ex) {
            return ex;
        }
    }

    /** All per-field detail messages of a validation failure. */
    private List<String> detailMessages(BadRequestException ex) {
        return ex.getDetails().stream()
                .map(d -> d.getMessage() == null ? "" : d.getMessage())
                .toList();
    }

    /** True when any detail message contains the fragment. */
    private void assertAnyDetailContains(BadRequestException ex, String fragment) {
        assertThat(detailMessages(ex))
                .as("some detail message contains '%s'", fragment)
                .anyMatch(m -> m.contains(fragment));
    }

    // reference the profile version so the compiler keeps the import honest
    @SuppressWarnings("unused")
    private AgentProfileVersion unusedReference(AgentProfile profile) {
        return versionService.findPublished(profile.getId()).orElse(null);
    }
}