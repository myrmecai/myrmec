// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

import java.time.Instant;
import java.util.UUID;

/** session.closed payload (§9): cleanup confirmed, slot returned. */
public record SessionClosedPayload(
        UUID sessionId,
        Instant closedAt,
        String reasonCode) {}
