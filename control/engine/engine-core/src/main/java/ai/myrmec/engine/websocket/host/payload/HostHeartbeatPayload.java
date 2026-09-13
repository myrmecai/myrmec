// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

import java.time.Instant;

/** host.heartbeat payload (§6.4). Counts are reconciliation signals only. */
public record HostHeartbeatPayload(
        Instant observedAt,
        int effectivePoolSize,
        int activeSessionCount,
        int pendingOfferCount,
        String health) {
}
