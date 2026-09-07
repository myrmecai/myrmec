// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.ValidationDetail;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Publication-time validation of a workflow that contains
 * {@code ORCHESTRATOR} steps (plan Feature 10, design §16.1).
 *
 * <p>Mirrors the DSL schema's cross-reference rules the TypeScript runner
 * enforces, plus the engine-mode rules that need engine rows:</p>
 * <ul>
 *   <li>{@code ORCHESTRATOR} steps require the orchestration map;
 *       {@code INFERENCE} steps reject it.</li>
 *   <li>Every {@code ORCHESTRATOR} step carries one identical
 *       {@code agentProfileCode} alias — a workflow-local name, never a
 *       mutable display name.</li>
 *   <li>Publication supplies an explicit {@code profileBindings} map from
 *       that alias to an existing Agent Profile UUID; the binding is
 *       required and the referenced profile must exist and be ACTIVE.</li>
 *   <li>Worker/model/verifier references inside the orchestration map:
 *       known model codes, no duplicate worker names, verifiers drawn from
 *       the worker catalog, declared dependencies known.</li>
 *   <li>Command-template names referenced by workers must exist in the
 *       bound Profile's PUBLISHED version command templates.</li>
 *   <li>Step-level {@code retryPolicy} sanity: non-negative bounded
 *       integers, initial ≤ max backoff.</li>
 * </ul>
 */
@Service
public class OrchestrationDefinitionValidator {

    private final ai.myrmec.engine.agent.AgentProfileRepository agentProfileRepository;
    private final ai.myrmec.engine.agent.AgentProfileVersionService agentProfileVersionService;

    public OrchestrationDefinitionValidator(
            ai.myrmec.engine.agent.AgentProfileRepository agentProfileRepository,
            ai.myrmec.engine.agent.AgentProfileVersionService agentProfileVersionService) {
        this.agentProfileRepository = agentProfileRepository;
        this.agentProfileVersionService = agentProfileVersionService;
    }

    /**
     * Validate the stored step maps of a workflow about to be published.
     *
     * @param steps            the workflow's stored step maps (Jackson JSON)
     * @param profileBindings  workflow-local alias → Agent Profile UUID,
     *                         supplied at publication (may be null for
     *                         pure-inference workflows)
     */
    @SuppressWarnings("unchecked")
    public void validate(List<Map<String, Object>> steps, Map<String, UUID> profileBindings) {
        List<ValidationDetail> failures = new ArrayList<>();

        boolean anyOrchestrator = steps.stream().anyMatch(s -> "ORCHESTRATOR".equals(s.get("taskType")));

        // INFERENCE steps reject the orchestration map.
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = steps.get(i);
            boolean orchestrator = "ORCHESTRATOR".equals(step.get("taskType"));
            if (orchestrator && step.get("orchestration") == null) {
                failures.add(ValidationDetail.of("steps[" + i + "].orchestration",
                        "REQUIRED", "ORCHESTRATOR step requires the orchestration map."));
            }
            if (!orchestrator && step.get("orchestration") != null) {
                failures.add(ValidationDetail.of("steps[" + i + "].orchestration",
                        "INVALID_VALUE", "INFERENCE steps cannot carry an orchestration map."));
            }
        }

        if (!anyOrchestrator) {
            // Pure-inference workflow — nothing orchestration-specific to check.
            if (!failures.isEmpty()) {
                throw new BadRequestException("Workflow validation failed.", failures);
            }
            return;
        }

        // One identical agentProfileCode across every orchestration step.
        Set<String> profileCodes = new HashSet<>();
        for (Map<String, Object> step : steps) {
            if (!"ORCHESTRATOR".equals(step.get("taskType"))) continue;
            Map<String, Object> orch = (Map<String, Object>) step.get("orchestration");
            if (orch == null) continue;
            Object code = orch.get("agentProfileCode");
            if (code != null) profileCodes.add(code.toString());
        }
        if (profileCodes.size() > 1) {
            failures.add(ValidationDetail.of("workflow",
                    "INVALID_VALUE", "All orchestration steps must use one agentProfileCode in V1."));
        }

        // The alias must be explicitly bound to an existing ACTIVE profile.
        String alias = profileCodes.isEmpty() ? null : profileCodes.iterator().next();
        if (alias != null) {
            if (profileBindings == null || !profileBindings.containsKey(alias)) {
                failures.add(ValidationDetail.of("profileBindings",
                        "REQUIRED", "Missing publication binding for agentProfileCode '" + alias + "'."));
            } else {
                UUID profileId = profileBindings.get(alias);
                var profileOpt = agentProfileRepository.findById(profileId);
                if (profileOpt.isEmpty() || profileOpt.get().getStatus()
                        != ai.myrmec.engine.agent.AgentProfile.Status.ACTIVE) {
                    failures.add(ValidationDetail.of("profileBindings",
                            "INVALID_VALUE",
                            "Binding for '" + alias + "' must reference an existing ACTIVE agent profile."));
                } else {
                    // Command templates come from the pinned published version.
                    var published = agentProfileVersionService.findPublished(profileId);
                    if (published.isEmpty()) {
                        failures.add(ValidationDetail.of("profileBindings",
                                "INVALID_VALUE",
                                "Bound agent profile '" + alias + "' has no published version."));
                    } else {
                        failures.addAll(validateTemplatesAndSteps(steps, published.get()));
                    }
                }
            }
        }

        if (!failures.isEmpty()) {
            throw new BadRequestException("Workflow validation failed.", failures);
        }
    }

    /** Cross-reference checks against the pinned published version. */
    private List<ValidationDetail> validateTemplatesAndSteps(
            List<Map<String, Object>> steps,
            ai.myrmec.engine.agent.AgentProfileVersion published) {
        List<ValidationDetail> failures = new ArrayList<>();

        Set<String> templateNames = commandTemplateNames(published);
        Set<String> stepIds = new HashSet<>();
        steps.forEach(s -> stepIds.add(String.valueOf(s.get("id"))));

        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = steps.get(i);
            if (!"ORCHESTRATOR".equals(step.get("taskType"))) continue;
            String stepId = String.valueOf(step.get("id"));
            Map<String, Object> orch = (Map<String, Object>) step.get("orchestration");
            if (orch == null) continue;

            // Known dependencies.
            Object deps = orch.get("dependsOn") != null ? orch.get("dependsOn") : step.get("dependsOn");
            if (deps instanceof List<?> depList) {
                for (Object dep : depList) {
                    if (!stepIds.contains(String.valueOf(dep))) {
                        failures.add(ValidationDetail.of("steps[" + i + "].dependsOn",
                                "INVALID_VALUE", "Unknown dependency: " + dep));
                    }
                }
            }

            // Worker catalog: duplicate names, template references.
            Set<String> workerNames = new HashSet<>();
            Object workersRaw = orch.get("workers");
            if (workersRaw instanceof List<?> workers) {
                for (int wi = 0; wi < workers.size(); wi++) {
                    if (!(workers.get(wi) instanceof Map<?, ?> worker)) continue;
                    String name = String.valueOf(worker.get("name"));
                    if (!workerNames.add(name)) {
                        failures.add(ValidationDetail.of(
                                "steps[" + i + "].orchestration.workers[" + wi + "].name",
                                "INVALID_VALUE", "Duplicate worker name: " + name));
                    }
                    Object commands = worker.get("allowedCommands");
                    if (commands instanceof List<?> commandList) {
                        for (Object cmd : commandList) {
                            if (!templateNames.contains(String.valueOf(cmd))) {
                                failures.add(ValidationDetail.of(
                                        "steps[" + i + "].orchestration.workers[" + wi + "].allowedCommands",
                                        "INVALID_VALUE",
                                        "Command template '" + cmd + "' is not defined by the bound "
                                                + "profile's published version."));
                            }
                        }
                    }
                }
            }

            // Verifiers must be worker names.
            Object criteria = orch.get("completionCriteria");
            if (criteria instanceof Map<?, ?> completion) {
                Object verifiers = completion.get("requireVerificationBy");
                if (verifiers instanceof List<?> verifierList) {
                    for (Object verifier : verifierList) {
                        if (!workerNames.contains(String.valueOf(verifier))) {
                            failures.add(ValidationDetail.of(
                                    "steps[" + i + "].orchestration.completionCriteria",
                                    "INVALID_VALUE",
                                    "Verifier not in worker catalog: " + verifier));
                        }
                    }
                }
            }

            // retryPolicy sanity (step level).
            failures.addAll(validateRetryPolicy(step, i, stepId));
        }
        return failures;
    }

    private List<ValidationDetail> validateRetryPolicy(Map<String, Object> step, int i, String stepId) {
        List<ValidationDetail> failures = new ArrayList<>();
        Object policy = step.get("retryPolicy");
        if (!(policy instanceof Map<?, ?> retry)) {
            return failures;
        }
        Integer maxRetries = intOf(retry.get("maxRetries"));
        if (maxRetries != null && maxRetries < 0) {
            failures.add(ValidationDetail.of("steps[" + i + "].retryPolicy.maxRetries",
                    "INVALID_VALUE", "maxRetries must be non-negative."));
        }
        Long initial = longOf(retry.get("initialBackoffSeconds"));
        Long max = longOf(retry.get("maxBackoffSeconds"));
        if (initial != null && initial < 0) {
            failures.add(ValidationDetail.of("steps[" + i + "].retryPolicy.initialBackoffSeconds",
                    "INVALID_VALUE", "initialBackoffSeconds must be non-negative."));
        }
        if (max != null && max < 0) {
            failures.add(ValidationDetail.of("steps[" + i + "].retryPolicy.maxBackoffSeconds",
                    "INVALID_VALUE", "maxBackoffSeconds must be non-negative."));
        }
        if (initial != null && max != null && initial > max) {
            failures.add(ValidationDetail.of("steps[" + i + "].retryPolicy",
                    "INVALID_VALUE", "initialBackoffSeconds must not exceed maxBackoffSeconds."));
        }
        return failures;
    }

    /** Parse the version's commandTemplates JSON into template names. */
    private Set<String> commandTemplateNames(ai.myrmec.engine.agent.AgentProfileVersion published) {
        Set<String> names = new HashSet<>();
        String json = published.getCommandTemplates();
        if (json == null || json.isBlank()) {
            return names;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            node.fieldNames().forEachRemaining(names::add);
        } catch (Exception e) {
            // Malformed stored JSON is a profile problem, surfaced as "no templates"
        }
        return names;
    }

    private static Integer intOf(Object raw) {
        if (raw instanceof Number num) return num.intValue();
        if (raw == null) return null;
        try {
            return Integer.parseInt(raw.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long longOf(Object raw) {
        if (raw instanceof Number num) return num.longValue();
        if (raw == null) return null;
        try {
            return Long.parseLong(raw.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}