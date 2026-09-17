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
 *
 * <p>§21.4 wire vocabulary (breaking): the session's service family rides the
 * wire as {@code kind} (values "CONVERSATION"/"WORKFLOW" unchanged); the
 * model block's endpoint field is {@code endpoint}; a tool's JSON schema
 * field is {@code inputSchema}; a knowledge-source handle identifies itself
 * by {@code id}. The §7.3 dispatch-context fields {@code executionMode}
 * ("ORCHESTRATION" on orchestration sessions, null otherwise) and {@code ref}
 * (the orchestration external reference, null otherwise) are additive.</p>
 *
 * <p>§7.5 additive: {@code channel} carries the dedicated-transport offer —
 * endpoint + single-use short-lived token. Null when the channel feature is
 * disabled ({@code myrmec.channel.enabled}).
 */
public record SessionOpenPayload(
        UUID sessionId,
        String kind,             // §21.4: "WORKFLOW" or "CONVERSATION"
        UUID projectId,
        UUID profileVersionId,   // pinned agent-profile version
        ModelConfig model,
        WorkspaceConfig workspace,  // nullable
        List<ToolDefinition> tools,
        List<KnowledgeSourceHandle> knowledgeSources,
        boolean autoHitlOnDestructive,  // project HITL policy
        String executionMode,             // §7.3: "ORCHESTRATION" or null
        String ref,                       // §7.3: orchestration external reference or null
        Map<String, Object> orchestration,  // §16.2 assignment; null for non-orchestration
        String assignmentDigest,          // §16.3 sha-256 of the canonical assignment bytes
        List<SessionCredential> credentials,  // sealed session secrets (design §9); null/empty when keyless
        ChannelOffer channel) {           // §7.5 dedicated-transport offer; null when disabled

    /** §7.5: the dedicated-transport offer on session.open. */
    public record ChannelOffer(String endpoint, String token) {}

    /** Copy the context with the §16.2 assignment installed (orchestration only). */
    public SessionOpenPayload withAssignment(Map<String, Object> assignment, String digest) {
        return new SessionOpenPayload(sessionId, kind, projectId, profileVersionId,
                model, workspace, tools, knowledgeSources, autoHitlOnDestructive,
                executionMode, ref, assignment, digest, credentials, channel);
    }

    /** Copy the context with the §7.5 channel offer installed. */
    public SessionOpenPayload withChannel(ChannelOffer offer) {
        return new SessionOpenPayload(sessionId, kind, projectId, profileVersionId,
                model, workspace, tools, knowledgeSources, autoHitlOnDestructive,
                executionMode, ref, orchestration, assignmentDigest, credentials, offer);
    }

    public record ModelConfig(
            String provider,
            String modelId,
            String endpoint,       // §21.4: nullable
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
            Map<String, Object> inputSchema,  // §21.4: JSON-schema
            String riskClass) {}              // "SAFE", "DESTRUCTIVE", "IRREVERSIBLE"

    public record KnowledgeSourceHandle(
            UUID id,               // §21.4
            String name,
            String description) {}
}