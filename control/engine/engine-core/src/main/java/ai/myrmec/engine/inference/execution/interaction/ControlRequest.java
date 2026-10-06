// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import java.util.UUID;

/**
 * Plan section 4 POST /controls body: {clientRequestId, action}.
 * HOLD/CONTINUE only — CANCEL is the /cancel route (§22.4).
 */
public record ControlRequest(UUID clientRequestId, String action) {

    public ControlRequest {
        if (clientRequestId == null) {
            throw new IllegalArgumentException("clientRequestId is required");
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action is required");
        }
        if (!"HOLD".equals(action) && !"CONTINUE".equals(action)) {
            // §22.4: HOLD and CONTINUE are the only /controls actions.
            throw new IllegalArgumentException("action must be HOLD or CONTINUE");
        }
    }
}