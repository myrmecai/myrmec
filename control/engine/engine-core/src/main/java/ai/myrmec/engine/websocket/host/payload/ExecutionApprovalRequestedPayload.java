// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

import java.time.Instant;
import java.util.UUID;

/** execution.approval.requested (§8.7) — persisted BEFORE execution.paused is sent. */
public record ExecutionApprovalRequestedPayload(
        UUID executionId, UUID dispatchId, String approvalRequestId,
        Action action, String snapshotTreeHash, String stateDigest, Instant expiresAt) {

    public record Action(String actionId, String type, String riskClass,
                         String summary, String digest) {}
}
