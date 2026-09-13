// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

import java.util.Map;

/**
 * The §14 error contract. Never includes credentials, prompts, tool
 * results, or stack traces.
 */
public record ProtocolErrorPayload(
        String code,
        String message,
        boolean retryable,
        String offendingMessageId,
        String scope,
        Map<String, Object> details) {
}
