// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

import java.time.Instant;
import java.util.UUID;

/** execution.paused (§8.6) — terminal for the execution AND the session (§11.2 note). */
public record ExecutionPausedPayload(
        UUID executionId, Instant pausedAt, String reasonCode,
        OrchestrationContinuation continuation,     // orchestration only; null for conversations
        Suspension suspension,                      // orchestration only
        ConversationContinuation conversationContinuation,  // conversation only
        Usage usage) {

    public record OrchestrationContinuation(
            String continuationId, String continuationRef, String recoveryProviderId,
            String portability, String snapshotRef, String snapshotDigest,
            String snapshotTreeHash, Integer workspaceRevision, String stateDigest) {}

    public record Suspension(
            String approvalRequestId, PendingAction pendingAction, Instant expiresAt) {}

    public record PendingAction(String actionId, String type, String riskClass,
                                String summary, String digest) {}

    public record ConversationContinuation(
            String approvalRequestId, String pendingActionId, String pendingActionDigest) {}

    /** Protocol 8.6: orchestration usage is function-call/token totals; a
     * conversation pause carries model/token accounting instead. Both
     * shapes bind this one record; the absent variant's fields stay null
     * (the socket's ObjectMapper fails on unknown keys, so the sender
     * must emit only the keys its variant carries). */
    public record Usage(String modelId, Long inputTokens, Long outputTokens,
                        Long orchestrationFunctionCalls, Long totalTokens) {}
}
