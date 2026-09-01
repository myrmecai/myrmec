// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.message.payload;

import java.util.UUID;

/**
 * Payload for the {@code inference.cancelled} frame (§5.5).
 * Agent → Engine. Ack of cancellation (+ partial content).
 */
public record InferenceCancelledPayload(
        UUID requestId,
        UUID sessionId,
        int sequenceNo,
        String partialContent) {}  // nullable