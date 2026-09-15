// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

import ai.myrmec.engine.inference.InferenceMessage;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** execution.start payload (Â§8.1): one turn (conversation) or one orchestration attempt. */
public record ExecutionStartPayload(
        UUID executionId,
        UUID sessionId,
        Integer sequenceNo,            // strictly increasing per conversation session; null for orchestration
        String requestId,              // conversation turn correlation; null for orchestration
        Instant deadline,
        Input input,                   // null for orchestration (assignment installed at session.open)
        ToolPolicy toolPolicy,         // null for orchestration
        Output output) {               // null for orchestration

    public record Input(
            List<InferenceMessage> messages,   // reuse the legacy Â§5.3 message record â€” same shape
            List<Attachment> attachments,
            ConversationContinuation conversationContinuation) {}

    public record Attachment(String id, String name, String contentType, String uri) {}

    public record ConversationContinuation(
            String approvalRequestId, String decisionId,
            String pendingActionId, String pendingActionDigest) {}

    public record ToolPolicy(List<String> activeToolNames, String approvalMode) {}

    public record Output(boolean stream, Integer responseSequenceNo, String format) {}
}
