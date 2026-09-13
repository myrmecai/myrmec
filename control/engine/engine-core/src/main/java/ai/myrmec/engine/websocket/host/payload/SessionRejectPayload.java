// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

import java.util.UUID;

/** session.reject payload (§7.2): refuse and release the reservation. */
public record SessionRejectPayload(
        UUID allocationId,
        UUID sessionId,
        String reasonCode,   // e.g. NO_CAPACITY, CAPABILITY_MISMATCH
        String message,
        boolean retryable) {}
