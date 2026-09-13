// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

import java.time.Instant;
import java.util.UUID;

/** execution.cancel (engine→host, §8.8). */
public record ExecutionCancelPayload(
        UUID executionId, UUID dispatchId, String reasonCode,
        Instant requestedAt, int gracePeriodSeconds) {}
