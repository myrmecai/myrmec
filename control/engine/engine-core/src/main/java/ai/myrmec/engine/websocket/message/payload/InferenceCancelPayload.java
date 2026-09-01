// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.message.payload;

import java.util.UUID;

/**
 * Payload for the {@code inference.cancel} frame (§5.5).
 * Engine → Agent. Cancel an in-flight turn.
 */
public record InferenceCancelPayload(
        UUID requestId,
        UUID sessionId) {}