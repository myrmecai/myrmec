// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

/**
 * Task 8: admission refused by the engine's OWN outbound secret scan
 * (§22.6's independent redaction layer). Fail closed — the §4 route maps
 * this onto 400 VALIDATION_ERROR with the CAPTURE_BLOCKED field error.
 */
public class InteractionCaptureBlockedException extends RuntimeException {

    public InteractionCaptureBlockedException(String message) {
        super(message);
    }
}