// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.message.payload;

import java.util.UUID;

/**
 * Payload for the {@code inference.accept} frame (§5.5).
 * Agent → Engine. Agent acknowledges it has started the turn.
 */
public record InferenceAcceptPayload(
        UUID requestId,
        UUID sessionId) {}