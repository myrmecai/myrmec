// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** execution.complete (§8.5). */
public record ExecutionCompletePayload(
        UUID executionId, Instant completedAt,
        Result result, Usage usage) {

    public record Result(String content, Map<String, Object> structured, List<String> artifacts) {}

    public record Usage(String modelId, Long inputTokens, Long outputTokens, Long durationMs) {}
}
