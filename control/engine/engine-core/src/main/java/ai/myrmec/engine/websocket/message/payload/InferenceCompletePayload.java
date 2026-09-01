// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.message.payload;

import java.util.UUID;

/**
 * Payload for the {@code inference.complete} frame (§5.5).
 * Agent → Engine. Final assistant content + token usage.
 */
public record InferenceCompletePayload(
        UUID requestId,
        UUID sessionId,
        int sequenceNo,
        String content,
        Integer tokenCount,      // nullable
        String modelCode) {}     // nullable