// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine._protocol.payload;

import java.time.Instant;
import java.util.UUID;

/** execution.accept (§8.2). */
public record ExecutionAcceptPayload(
        UUID executionId, Instant startedAt, String resolvedModelId,
        UUID dispatchId, String assignmentDigest) {}
