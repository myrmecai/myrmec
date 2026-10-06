// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

import java.util.UUID;

/**
 * section 22.7 execution.control.request — the host's durable proposal for an
 * engine-authorized control, raised by the chat tool boundary.
 *
 * <p>Action is HOLD, CONTINUE, or CANCEL. The engine derives actorUserId from
 * its persisted interaction; the host CANNOT supply an actor or confirmation
 * grant. A proposal is valid only for an admitted pending interaction.</p>
 */
public record ExecutionControlRequestPayload(
        UUID executionId,
        UUID dispatchId,
        UUID interactionId,
        UUID controlRequestId,
        Action action,
        String explanation) {

    /** section 22.7: proposals may ask for all three V1 controls. */
    public enum Action { HOLD, CONTINUE, CANCEL }

    public ExecutionControlRequestPayload {
        if (executionId == null || dispatchId == null || interactionId == null) {
            throw new IllegalArgumentException(
                    "execution.control.request requires executionId, dispatchId, interactionId");
        }
        if (controlRequestId == null) {
            throw new IllegalArgumentException(
                    "controlRequestId is sdk-generated (section 22.7)");
        }
        if (action == null) {
            throw new IllegalArgumentException("action is required");
        }
    }
}