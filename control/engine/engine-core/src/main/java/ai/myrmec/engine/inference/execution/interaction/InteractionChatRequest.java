// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import java.util.UUID;

/**
 * §4 POST /interactions request body: the idempotent client request id and
 * the chat text. camelCase JSON (§4 conventions).
 */
public record InteractionChatRequest(UUID clientRequestId, String text) {
    public InteractionChatRequest {
        if (clientRequestId == null) {
            throw new IllegalArgumentException("clientRequestId is required");
        }
    }
}