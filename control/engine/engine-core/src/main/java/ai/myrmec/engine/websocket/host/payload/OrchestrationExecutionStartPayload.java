// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

import java.time.Instant;
import java.util.UUID;

/** §8.1 orchestration shape: the assignment is already installed at session.open. */
public record OrchestrationExecutionStartPayload(
        UUID executionId, UUID sessionId,
        UUID dispatchId, UUID attemptId, String assignmentDigest, Instant deadline) {}
