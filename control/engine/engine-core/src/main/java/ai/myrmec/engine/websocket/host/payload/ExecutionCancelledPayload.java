// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

import java.time.Instant;
import java.util.UUID;

/** execution.cancelled (host→engine, §8.8). */
public record ExecutionCancelledPayload(
        UUID executionId, UUID dispatchId, Instant cancelledAt, String reasonCode) {}
