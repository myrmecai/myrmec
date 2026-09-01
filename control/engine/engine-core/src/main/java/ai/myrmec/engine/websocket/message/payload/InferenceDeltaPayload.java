// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.message.payload;

import java.util.UUID;

/**
 * Payload for the {@code inference.delta} frame (§5.5).
 * Agent → Engine. Streaming text chunk (only when stream=true).
 */
public record InferenceDeltaPayload(
        UUID requestId,
        UUID sessionId,
        int sequenceNo,
        int deltaIndex,
        String content) {}