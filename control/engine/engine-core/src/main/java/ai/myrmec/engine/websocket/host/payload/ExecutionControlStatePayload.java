// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

import java.time.Instant;
import java.util.UUID;

/**
 * section 22.4 execution.control.state — the host's durable observed-state
 * report for the orchestrated hold overlay.
 *
 * <p>{@code stateSequence} increases for EVERY new state notification within
 * the execution; {@code controlRevision} is the highest accepted engine
 * revision (initially 0). REJECTED additionally carries
 * {@code rejectedControlRevision}; the overlay's actual value rides
 * {@code effectiveState}. {@code safePoint} is present on HELD;
 * {@code idleResumeAt} only while HELD with the idle clock armed.</p>
 */
public record ExecutionControlStatePayload(
        UUID executionId,
        UUID dispatchId,
        long controlRevision,
        long stateSequence,
        Status status,
        EffectiveState effectiveState,
        String reasonCode,
        Instant changedAt,
        Instant idleResumeAt,
        SafePoint safePoint,
        Long rejectedControlRevision,
        String errorCode) {

    /** section 22.4: observed overlay statuses (PAUSED is a different frame). */
    public enum Status { HOLD_REQUESTED, HELD, RUNNING, REJECTED }

    /** section 22.4: the actual overlay preserved across rejections. */
    public enum EffectiveState { RUNNING, HOLD_REQUESTED, HELD }

    /** section 22.4: where the cooperative safe point parked the work. */
    public enum SafePoint {
        BEFORE_MODEL_CALL, BEFORE_TOOL_EXECUTION,
        BEFORE_HELPER_CALL, BEFORE_GIT_EFFECT
    }

    public ExecutionControlStatePayload {
        if (executionId == null || dispatchId == null) {
            throw new IllegalArgumentException(
                    "execution.control.state requires executionId and dispatchId");
        }
        if (controlRevision < 0) {
            throw new IllegalArgumentException("controlRevision is initially 0");
        }
        if (stateSequence < 1) {
            throw new IllegalArgumentException(
                    "stateSequence increases for every new state notification (starts at 1)");
        }
        if (status == null || effectiveState == null) {
            throw new IllegalArgumentException("status and effectiveState are required");
        }
        if (changedAt == null) {
            throw new IllegalArgumentException("changedAt is required");
        }
        if (status == Status.REJECTED && rejectedControlRevision == null) {
            throw new IllegalArgumentException(
                    "a REJECTED state carries rejectedControlRevision (section 22.4)");
        }
    }
}