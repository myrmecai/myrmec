// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import java.util.UUID;

/**
 * Plan section 4 POST /cancel body: {clientRequestId, confirmed: true}.
 * Explicit user confirmation — confirmed:false is a 400 VALIDATION_ERROR;
 * no model participates (§22.7 "A direct Cancel button uses the same
 * service with an explicit confirmed user action").
 */
public record ConfirmedCancelRequest(UUID clientRequestId, boolean confirmed) {

    public ConfirmedCancelRequest {
        if (clientRequestId == null) {
            throw new IllegalArgumentException("clientRequestId is required");
        }
    }
}