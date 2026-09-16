// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.message.payload;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Payload for the {@code session.open} frame (§5.2).
 * Sent once when a worker binds to a conversation or is assigned a workflow execution.
 *
 * <p>Feature 10 (§16.2/§16.3, unified protocol §8.1): an ORCHESTRATOR step's
 * complete self-contained {@code OrchestrationAssignment} is installed at
 * {@code session.open} — the assignment IS the orchestration session's context,
 * so it rides this frame as {@code orchestration} plus its canonical
 * {@code assignmentDigest}. {@code execution.start} then references the stored
 * bytes by {@code dispatchId/attemptId/assignmentDigest} only. Both fields are
 * null for conversation sessions and for ordinary INFERENCE steps.</p>
 */
public record SessionOpenPayload(
        UUID sessionId,
        String serviceType,      // "WORKFLOW" or "CONVERSATION"
        UUID projectId,
        UUID profileVersionId,   // pinned agent-profile version
        ModelConfig model,
        WorkspaceConfig workspace,  // nullable
        List<ToolDefinition> tools,
        List<KnowledgeSourceHandle> knowledgeSources,
        boolean autoHitlOnDestructive,  // project HITL policy
        Map<String, Object> orchestration,  // §16.2 assignment; null for non-orchestration
        String assignmentDigest,          // §16.3 sha-256 of the canonical assignment bytes
        List<SessionCredential> credentials) {  // sealed session secrets (design §9); null/empty when keyless

    /** Copy the context with the §16.2 assignment installed (orchestration only). */
    public SessionOpenPayload withAssignment(Map<String, Object> assignment, String digest) {
        return new SessionOpenPayload(sessionId, serviceType, projectId, profileVersionId,
                model, workspace, tools, knowledgeSources, autoHitlOnDestructive,
                assignment, digest, credentials);
    }

    public record ModelConfig(
            String provider,
            String modelId,
            String apiEndpoint,    // nullable
            String credentialRef,  // references credentials[] entry (design §9); null = keyless
            Map<String, Object> parameters) {}

    public record WorkspaceConfig(
            String repoUrl,
            String branch,
            String subPath,        // nullable
            String credentialRef) {}  // references credentials[] entry; null when no repo credential

    public record ToolDefinition(
            String name,
            String description,
            Map<String, Object> parameters,  // JSON-schema
            String riskClass) {}              // "SAFE", "DESTRUCTIVE", "IRREVERSIBLE"

    public record KnowledgeSourceHandle(
            UUID knowledgeSourceId,
            String name,
            String description) {}
}