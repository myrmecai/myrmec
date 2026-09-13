// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

import java.time.Instant;
import java.util.UUID;

/** session.accept payload (§7.2): the host commits a local slot. */
public record SessionAcceptPayload(
        UUID allocationId,
        UUID sessionId,
        Instant acceptedAt) {}
