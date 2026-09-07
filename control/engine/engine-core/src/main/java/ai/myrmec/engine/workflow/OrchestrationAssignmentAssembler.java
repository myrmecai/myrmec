// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import ai.myrmec.engine.inference.OrchestrationSourceResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Assembles the complete self-contained {@code OrchestrationAssignment}
 * (design §16.2 / §7) for an orchestration task attempt and serializes it
 * into the {@code InferenceAssignPayload.orchestration} field.
 *
 * <p>Assembly steps (§16.2):</p>
 * <ol>
 *   <li>{@code runId} = workflow request ID.</li>
 *   <li>{@code dispatchId} = the attempt UUID (V1).</li>
 *   <li>Complete {@code DispatchIdentity} tuple: workflow ID, runId, selected
 *       step ID, task ID, attempt ID, immutable attempt ordinal, dispatchId.</li>
 *   <li>Source resolved from the workflow artifacts repo (project workspace
 *       fallback), {@code sourceBranch} resolved to an immutable base commit
 *       via {@link OrchestrationSourceResolver}, unique target branch.</li>
 *   <li>Secret IDs replaced with session-scoped credential references.</li>
 *   <li>Every step/worker model resolved.</li>
 *   <li>Complete assignment — no later registry lookup: full dispatch tuple,
 *       non-secret models, resolved source, the {@code ExecutionPolicy}
 *       block assembled from the pinned Profile version, exactly one
 *       selected step, optional typed continuation (fresh attempts omit).</li>
 * </ol>
 *
 * <p>The serialized JSON must pass the TypeScript
 * {@code orchestrationAssignmentSchema}; the canonical digest is SHA-256
 * over the serialized bytes (for the durable dispatch row and
 * {@code inference.accept} correlation).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrchestrationAssignmentAssembler {

    private final ObjectMapper objectMapper;
    private final WorkflowRepository workflowRepository;
    private final WorkflowRequestRepository requestRepository;
    private final WorkflowTaskRepository workflowTaskRepository;
    private final OrchestrationRunRepository runRepository;
    private final ai.myrmec.engine.model.ModelService modelService;
    private final OrchestrationSourceResolver sourceResolver;

    /**
     * Assemble and serialize the assignment for one attempt.
     *
     * @param task     the orchestration task
     * @param attempt  the attempt being dispatched (dispatchId = attempt UUID)
     * @param profileVersion the run's pinned published Profile version
     * @param continuation optional typed continuation directive for
     *                     retry/review continuation attempts; null for fresh
     */
    @Transactional
    public AssembledAssignment assemble(WorkflowTask task, TaskAttempt attempt,
                                        ai.myrmec.engine.agent.AgentProfileVersion profileVersion,
                                        ContinuationDirective continuation) {
        try {
            // Re-attach the graph inside this transaction: task → request →
            // workflow → project may be detached proxies when called from
            // outside a persistence context.
            task = workflowTaskRepository.findById(task.getId())
                    .orElse(task);
            WorkflowRequest request = task.getRequest();
            Workflow workflow = workflowRepository.findById(request.getWorkflow().getId())
                    .orElse(request.getWorkflow());

            Map<String, Object> stepDef = findStep(workflow.getSteps(), task.getStepId());
            if (stepDef == null) {
                throw new IllegalStateException(
                        "Step " + task.getStepId() + " not found on workflow " + workflow.getId());
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> orchestration =
                    (Map<String, Object>) stepDef.get("orchestration");
            if (orchestration == null) {
                throw new IllegalStateException(
                        "Step " + task.getStepId() + " is not an ORCHESTRATOR step");
            }

            // §16.2 (1)(2)(3): durable dispatch identity without a message
            // sequence — dispatchId = attempt UUID in V1.
            Map<String, Object> dispatch = new LinkedHashMap<>();
            dispatch.put("workflowId", workflow.getId().toString());
            dispatch.put("runId", request.getId().toString());
            dispatch.put("stepId", task.getStepId());
            dispatch.put("taskId", task.getId().toString());
            dispatch.put("attemptId", attempt.getId().toString());
            dispatch.put("attemptOrdinal", attempt.getAttemptNumber());
            dispatch.put("dispatchId", attempt.getId().toString());
            if (continuation != null) {
                dispatch.put("continuationId", continuation.continuationId());
            }

            // §16.2 (4): resolved source — immutable base commit + unique
            // target branch.
            OrchestrationSourceResolver.ResolvedSource source =
                    sourceResolver.resolve(workflow, request);
            Map<String, Object> sourceMap = new LinkedHashMap<>();
            sourceMap.put("repoUrl", source.repoUrl());
            sourceMap.put("sourceBranch", source.sourceBranch());
            sourceMap.put("sourceBaseCommit", source.sourceBaseCommit());
            sourceMap.put("targetBranch", source.targetBranch());
            sourceMap.put("credentialRef", source.credentialRef());

            // §16.2 (6): every step/worker model, non-secret, with
            // session-scoped credential refs (never the key itself).
            List<Map<String, Object>> models = resolveModels(orchestration);

            // §16.2 (7): the ExecutionPolicy block from the pinned Profile
            // version — referenced command templates only.
            Map<String, Object> policy = buildPolicy(profileVersion, orchestration);

            // §16.2 (7): exactly one selected step — PROJECTED to the §7
            // authoring shape. The stored step map (WorkflowStepDto via
            // Jackson) carries engine-only fields (agentProfileId, prompt,
            // transitions, timeoutSeconds, maxRetries, pauseMode) that the
            // Agent's strict runtime schema rejects; the workflow-local
            // agentProfileCode alias lives INSIDE the stored orchestration
            // map (authoring convention) but §7 expects it at step level.
            Map<String, Object> selectedStep = projectStep(stepDef);

            Map<String, Object> assignment = new LinkedHashMap<>();
            assignment.put("schemaVersion", "1.0");
            assignment.put("dispatch", dispatch);
            assignment.put("models", models);
            assignment.put("source", sourceMap);
            assignment.put("policy", policy);
            assignment.put("step", selectedStep);
            if (continuation != null) {
                Map<String, Object> cont = new LinkedHashMap<>();
                cont.put("continuationId", continuation.continuationId());
                cont.put("previousDispatchId", continuation.previousDispatchId());
                // §7/§17.4: a continuation created by durable human
                // review carries the typed ApprovalDecision — the
                // runner validates every field against the restored
                // suspension before resuming the exact pending action.
                Map<String, Object> decision = decisionEnvelopeOf(task);
                if (decision != null) {
                    cont.put("decision", decision);
                }
                assignment.put("continuation", cont);
            }

            String json = objectMapper.writeValueAsString(assignment);
            String digest = OrchestrationIds.sha256Hex(json.getBytes());
            return new AssembledAssignment(assignment, json, digest);
        } catch (Exception e) {
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Assignment assembly failed", e);
        }
    }

    /**
     * The §7 {@code ApprovalDecision} envelope for an HITL-resume
     * attempt — built from the approval payload the decide path
     * enriched under the task lock. Null for retry continuations (no
     * human decision) and fresh attempts.
     */
    private Map<String, Object> decisionEnvelopeOf(WorkflowTask task) {
        Map<String, Object> payload = task.getApprovalPayload();
        if (payload == null || !"APPROVED".equals(payload.get("decisionStatus"))) {
            return null; // retry continuation or fresh attempt
        }
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("schemaVersion", "1.0");
        putIfPresent(decision, "decisionId", payload.get("decisionId"));
        putIfPresent(decision, "approvalRequestId", payload.get("approvalRequestId"));
        putIfPresent(decision, "continuationId", payload.get("suspensionContinuationId"));
        putIfPresent(decision, "previousDispatchId", payload.get("previousDispatchId"));
        decision.put("status", "APPROVED");
        putIfPresent(decision, "actionDigest", payload.get("actionDigest"));
        putIfPresent(decision, "stateDigest", payload.get("stateDigest"));
        putIfPresent(decision, "snapshotTreeHash", payload.get("snapshotTreeHash"));
        putIfPresent(decision, "workspaceGeneration", payload.get("workspaceGeneration"));
        putIfPresent(decision, "decidedAt", payload.get("decidedAt"));
        putIfPresent(decision, "expiresAt", payload.get("expiresAt"));
        return decision;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    /** The step/worker models referenced by the orchestration map. */
    private List<Map<String, Object>> resolveModels(Map<String, Object> orchestration) {
        List<Map<String, Object>> models = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String orchestratorCode = String.valueOf(orchestration.get("modelCode"));
        addModel(models, seen, orchestratorCode);
        Object workersRaw = orchestration.get("workers");
        if (workersRaw instanceof List<?> workers) {
            for (Object w : workers) {
                if (w instanceof Map<?, ?> worker) {
                    addModel(models, seen, String.valueOf(worker.get("modelCode")));
                }
            }
        }
        return models;
    }

    private void addModel(List<Map<String, Object>> models, Set<String> seen, String code) {
        if (!seen.add(code)) return;
        ai.myrmec.engine.model.Model model = modelService.findByCode(code);
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("code", model.getCode());
        def.put("provider", model.getProvider());
        def.put("modelId", model.getModelId());
        def.put("description", model.getName());
        def.put("apiEndpoint", model.getApiEndpoint());
        // §16.2 (5): session-scoped credential reference — the decrypted key
        // travels only inside the secure envelope, never in the assignment.
        def.put("credentialRef", "model:" + model.getCode());
        def.put("parameters", model.getDefaultParams() == null
                ? Map.of() : model.getDefaultParams());
        models.add(def);
    }

    /**
     * The ExecutionPolicy from the pinned version: allowed tools, the
     * referenced command templates only (§7: referenced-subset), isolation,
     * approval policy, git policy, retention.
     */
    private Map<String, Object> buildPolicy(
            ai.myrmec.engine.agent.AgentProfileVersion version,
            Map<String, Object> orchestration) {
        Map<String, Object> policy = new LinkedHashMap<>();

        // Referenced templates only: the ones this step's workers name.
        Map<String, Object> allTemplates = parseJsonMap(version.getCommandTemplates());
        Map<String, Object> referenced = new TreeMap<>();
        Set<String> referencedNames = new HashSet<>();
        if (orchestration.get("workers") instanceof List<?> workers) {
            for (Object w : workers) {
                if (w instanceof Map<?, ?> worker
                        && worker.get("allowedCommands") instanceof List<?> commands) {
                    commands.forEach(c -> referencedNames.add(String.valueOf(c)));
                }
            }
        }
        for (String name : referencedNames) {
            Object template = allTemplates.get(name);
            if (template != null) {
                referenced.put(name, template);
            }
        }
        policy.put("allowedTools", List.of(
                "read_file", "list_directory", "create_directory", "write_file", "execute_command"));
        policy.put("commandTemplates", referenced);
        policy.put("requiredIsolation", version.getRequiredIsolation() == null
                ? "TRUSTED_PROCESS" : version.getRequiredIsolation().name());
        policy.put("approvalPolicy", parseJsonMap(version.getApprovalPolicy()));
        Map<String, Object> gitPolicy = new LinkedHashMap<>();
        ai.myrmec.engine.workflow.OrchestrationPolicyJson git =
                OrchestrationPolicyJson.parse(version.getGitPolicy());
        gitPolicy.put("allowCheckpoint", git.allowCheckpoint);
        gitPolicy.put("allowPush", git.allowPush);
        policy.put("gitPolicy", gitPolicy);
        policy.put("workspaceRetentionSeconds",
                version.getWorkspaceRetentionSeconds() == null
                        ? 86400 : version.getWorkspaceRetentionSeconds());
        return policy;
    }

    private Map<String, Object> findStep(List<Map<String, Object>> steps, String stepId) {
        for (Map<String, Object> step : steps) {
            if (stepId.equals(step.get("id"))) {
                return step;
            }
        }
        return null;
    }

    /**
     * Project one stored step map to the §7 {@code OrchestrationStepAuthoring}
     * shape the Agent's strict runtime schema accepts: id, name, taskType,
     * agentProfileCode, dependsOn, retryPolicy, orchestration — and the
     * orchestration sub-map WITHOUT the engine-local agentProfileCode alias.
     * Engine-only fields (agentProfileId, prompt, transitions,
     * timeoutSeconds, maxRetries, pauseMode) never cross the wire.
     */
    private Map<String, Object> projectStep(Map<String, Object> stepDef) {
        Map<String, Object> projected = new LinkedHashMap<>();
        projected.put("id", stepDef.get("id"));
        projected.put("name", stepDef.get("name"));
        projected.put("taskType", "ORCHESTRATOR");

        @SuppressWarnings("unchecked")
        Map<String, Object> orch = (Map<String, Object>) stepDef.get("orchestration");
        Map<String, Object> orchOut = new LinkedHashMap<>(orch);
        // §7 shape: the alias sits at step level, not inside orchestration;
        // an orchestration-nested dependsOn (engine authoring convention)
        // moves to the step level where the runtime schema expects it.
        Object alias = orchOut.remove("agentProfileCode");
        projected.put("agentProfileCode", alias != null ? alias : "");

        Object deps = stepDef.get("dependsOn");
        if (deps == null) {
            Object nestedDeps = orchOut.remove("dependsOn");
            if (nestedDeps instanceof List<?> list) {
                deps = list;
            }
        }
        projected.put("dependsOn", deps instanceof List<?> list ? new ArrayList<>(list) : List.of());

        // retryPolicy: step-level map (§6) projected to the three schema
        // fields; a missing map defaults to the terminal-free policy.
        Map<String, Object> retryPolicy = new LinkedHashMap<>();
        Object rawPolicy = stepDef.get("retryPolicy");
        if (rawPolicy instanceof Map<?, ?> policyMap) {
            if (policyMap.get("maxRetries") instanceof Number n) {
                retryPolicy.put("maxRetries", n.intValue());
            }
            if (policyMap.get("initialBackoffSeconds") instanceof Number n) {
                retryPolicy.put("initialBackoffSeconds", n.intValue());
            }
            if (policyMap.get("maxBackoffSeconds") instanceof Number n) {
                retryPolicy.put("maxBackoffSeconds", n.intValue());
            }
        }
        if (!retryPolicy.containsKey("maxRetries")) {
            retryPolicy.put("maxRetries", 0);
        }
        if (!retryPolicy.containsKey("initialBackoffSeconds")) {
            retryPolicy.put("initialBackoffSeconds", 1);
        }
        if (!retryPolicy.containsKey("maxBackoffSeconds")
                || ((Number) retryPolicy.get("maxBackoffSeconds")).intValue()
                        < ((Number) retryPolicy.get("initialBackoffSeconds")).intValue()) {
            retryPolicy.put("maxBackoffSeconds", retryPolicy.get("initialBackoffSeconds"));
        }
        projected.put("retryPolicy", retryPolicy);

        projected.put("orchestration", orchOut);
        return projected;
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Malformed stored orchestration policy JSON — treating as empty");
            return new LinkedHashMap<>();
        }
    }

    /** A typed continuation directive for retry/review continuation attempts. */
    public record ContinuationDirective(String continuationId, String previousDispatchId) {}

    /** The assembled assignment: the object tree, canonical JSON, digest. */
    public record AssembledAssignment(
            Map<String, Object> assignment,
            String canonicalJson,
            String assignmentDigest) {}
}