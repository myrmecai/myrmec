// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

/**
 * Plan section 4 POST /control-requests/{id}/decision body:
 * {decision: "CONFIRM" | "DECLINE"}.
 */
public record Decision(String decision) {

    public Decision {
        if (decision == null || decision.isBlank()) {
            throw new IllegalArgumentException("decision is required");
        }
        if (!"CONFIRM".equals(decision) && !"DECLINE".equals(decision)) {
            throw new IllegalArgumentException("decision must be CONFIRM or DECLINE");
        }
    }
}