// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

/**
 * Task 6: a state-conflict refusal carrying the §22.7/section-4
 * {@code reasonCode} (e.g. EXECUTION_TERMINAL, CONFIRMATION_EXPIRED) for
 * the 409 RESOURCE_IN_USE error shape:
 * {@code details: [{ resourceType, blocking, count, reasonCode }]}.
 */
public class ExecutionStateException extends RuntimeException {

    private final String reasonCode;

    public ExecutionStateException(String reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public String getReasonCode() {
        return reasonCode;
    }
}