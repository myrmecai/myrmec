// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.workflow.dto;

import ai.myrmec.engine.workflow.PauseMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DTO for workflow step definition (stored as JSON through TEXT).
 *
 * <p>Feature 10 (design §16.1): gains {@code taskType} (defaulting to
 * {@code INFERENCE} for compatibility), a nullable {@code orchestration}
 * map for {@code ORCHESTRATOR} steps, and the step-level
 * {@code retryPolicy} map that supersedes the legacy bare
 * {@code maxRetries}. {@code ORCHESTRATOR} steps require the orchestration
 * map; {@code INFERENCE} steps reject it.</p>
 *
 * @param maxRetries deprecated legacy field — seeded from
 *                  {@code retryPolicy.maxRetries} when present; kept for
 *                  wire compatibility with existing clients.
 */
public record WorkflowStepDto(
        @NotBlank @Size(max = 50) String id,
        @NotBlank @Size(max = 100) String name,
        @NotNull UUID agentProfileId,
        String prompt,
        List<String> dependsOn,
        Map<String, String> transitions,
        Integer timeoutSeconds,
        Integer maxRetries,
        PauseMode pauseMode,
        String taskType,
        Map<String, Object> orchestration,
        Map<String, Object> retryPolicy
) {
    public WorkflowStepDto {
        if (dependsOn == null) dependsOn = List.of();
        if (transitions == null) transitions = Map.of();
        if (timeoutSeconds == null) timeoutSeconds = 300;
        if (pauseMode == null) pauseMode = PauseMode.NONE;
        if (taskType == null || taskType.isBlank()) taskType = "INFERENCE";
        // Legacy default: absent retryPolicy means maxRetries from the bare
        // field (or 0). Present retryPolicy.maxRetries wins.
        if (retryPolicy != null) {
            Object mr = retryPolicy.get("maxRetries");
            if (mr instanceof Number num) {
                maxRetries = num.intValue();
            } else if (mr != null) {
                maxRetries = Integer.parseInt(mr.toString());
            }
        }
        if (maxRetries == null) maxRetries = 0;
    }

    /** Pre-Feature-10 signature — compatibility overload. */
    public WorkflowStepDto(
            String id, String name, UUID agentProfileId, String prompt,
            List<String> dependsOn, Map<String, String> transitions,
            Integer timeoutSeconds, Integer maxRetries, PauseMode pauseMode) {
        this(id, name, agentProfileId, prompt, dependsOn, transitions,
                timeoutSeconds, maxRetries, pauseMode, null, null, null);
    }

    public boolean isOrchestrator() {
        return "ORCHESTRATOR".equals(taskType);
    }
}
