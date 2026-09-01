// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.message.payload;

import java.util.UUID;

/**
 * Payload for the {@code inference.failed} frame (§5.5).
 * Agent → Engine. Turn failed (with error code/hint).
 */
public record InferenceFailedPayload(
        UUID requestId,
        UUID sessionId,
        String errorCode,
        String message,
        String retryHint) {}  // nullable