// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.message.payload;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Payload for the {@code session.open} frame (§5.2).
 * Sent once when a worker binds to a conversation or is assigned a workflow execution.
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
        boolean autoHitlOnDestructive) {  // project HITL policy

    public record ModelConfig(
            String provider,
            String modelId,
            String apiEndpoint,    // nullable
            String apiKey,         // decrypted; nullable for keyless local models
            Map<String, Object> parameters) {}

    public record WorkspaceConfig(
            String repoUrl,
            String branch,
            String subPath,        // nullable
            String repoToken) {}   // decrypted; nullable

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