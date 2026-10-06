// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

import ai.myrmec.engine.inference.execution.interaction.InteractionProperties;

/**
 * section 22.4 execution.control — the engine's durable HOLD/CONTINUE command.
 *
 * <p>{@code controlRevision} starts at 1 and increases under an engine
 * execution-row lock. HOLD requires {@code holdPolicy} matching the session
 * policy; CONTINUE omits it. {@code controlRequestId} is present for
 * chat-origin commands, otherwise absent/null. Reason code is USER_REQUESTED
 * in V1.</p>
 */
public record ExecutionControlPayload(
        java.util.UUID executionId,
        java.util.UUID dispatchId,
        long controlRevision,
        Action action,
        String reasonCode,
        HoldPolicy holdPolicy,
        java.util.UUID controlRequestId) {

    /** section 22.4: V1 controls — HOLD and CONTINUE (CANCEL is execution.cancel). */
    public enum Action { HOLD, CONTINUE }

    /** section 22.4: the hold-specific policy the command arms; HOLD requires it. */
    public record HoldPolicy(int idleResumeAfterSeconds) {
        public HoldPolicy {
            if (idleResumeAfterSeconds < InteractionProperties.MIN_IDLE_RESUME_AFTER_SECONDS
                    || idleResumeAfterSeconds
                            > InteractionProperties.MAX_IDLE_RESUME_AFTER_SECONDS) {
                throw new IllegalArgumentException(
                        "holdPolicy.idleResumeAfterSeconds must be within ["
                                + InteractionProperties.MIN_IDLE_RESUME_AFTER_SECONDS
                                + ".."
                                + InteractionProperties.MAX_IDLE_RESUME_AFTER_SECONDS
                                + "] (got " + idleResumeAfterSeconds + ")");
            }
        }
    }

    public ExecutionControlPayload {
        if (executionId == null || dispatchId == null) {
            throw new IllegalArgumentException(
                    "execution.control requires executionId and dispatchId");
        }
        if (controlRevision < 1) {
            throw new IllegalArgumentException(
                    "controlRevision starts at 1 and only increases");
        }
        if (action == null) {
            throw new IllegalArgumentException("action is required");
        }
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode is required");
        }
        if (action == Action.HOLD && holdPolicy == null) {
            throw new IllegalArgumentException(
                    "HOLD requires holdPolicy (section 22.4: HOLD requires holdPolicy "
                            + "matching the session policy; CONTINUE omits it)");
        }
    }
}
