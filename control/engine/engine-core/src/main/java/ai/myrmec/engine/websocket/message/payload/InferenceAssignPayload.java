// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.message.payload;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Payload for the {@code inference.assign} frame (§5.3).
 * Sent per turn/step — carries only the per-call data.
 *
 * <p>Feature 10 (design §16.2/§16.3): {@code orchestration} carries the
 * complete self-contained {@code OrchestrationAssignment} as a nested
 * JSON object plus its canonical {@code assignmentDigest} for
 * {@code ORCHESTRATOR} tasks; absent for ordinary inference tasks.</p>
 */
public record InferenceAssignPayload(
        UUID requestId,           // conversation turn id, or workflow task attempt id
        UUID sessionId,           // MUST match a prior session.open
        String serviceType,       // "WORKFLOW" or "CONVERSATION"
        List<InferenceMessage> messages,  // fully assembled transcript
        List<String> activeToolNames,     // subset of session tools enabled for this turn; may be []
        GenerationConfig generation,      // nullable — optional per-turn overrides
        Integer timeoutSeconds,           // nullable
        boolean stream,                   // true for CONVERSATION, false for WORKFLOW
        ResponseRouting response,
        Map<String, Object> orchestration,  // §16.2 — complete assignment; null for ordinary inference
        String assignmentDigest) {         // §16.3 — sha-256 of the canonical assignment bytes

    public record GenerationConfig(
            Double temperature,
            Integer maxOutputTokens) {}

    public record ResponseRouting(
            int sequenceNo,           // assistant sequence no (conversation) / step index (workflow)
            String stepId) {}         // workflow step id (nullable for conversation turns)
}