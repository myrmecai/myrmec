// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

import java.time.Instant;
import java.util.UUID;

/** execution.failed (§8.5). */
public record ExecutionFailedPayload(
        UUID executionId, Instant failedAt, Error error, Usage usage) {

    public record Error(String code, String message, String category,
                        boolean retryable, Long retryAfterSeconds) {}

    public record Usage(String modelId, Long inputTokens, Long outputTokens, Long durationMs) {}
}
