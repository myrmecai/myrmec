// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine._protocol.payload;

import java.util.UUID;

/** execution.delta (§8.3) — ephemeral, at-most-once, never acknowledged. */
public record ExecutionDeltaPayload(
        UUID executionId, int index, String content, String contentType) {}
