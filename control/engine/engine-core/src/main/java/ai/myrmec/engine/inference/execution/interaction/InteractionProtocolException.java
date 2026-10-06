// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

/**
 * Task 8 boundary-layer protocol error (§22.3/§14): an outcome frame the
 * engine refuses at the service layer (IDENTITY_MISMATCH,
 * INVALID_MESSAGE). THE BOUNDARY HANDLER maps this onto the wire's
 * protocol.error with the SAME code; the user-API routes never see it
 * (outcomes are host→engine only).
 */
public class InteractionProtocolException extends RuntimeException {

    private final String code;

    public InteractionProtocolException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}