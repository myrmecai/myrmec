// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

import java.time.Instant;
import java.util.UUID;

/**
 * section 22.7 execution.control.request.resolved — the engine's durable
 * proposal disposition. This is NOT observed execution state.
 *
 * <p>{@code resolutionRevision} increases per request disposition; duplicates
 * reuse bytes. ACCEPTED includes the persisted {@code commandMessageId} and,
 * for HOLD/CONTINUE, {@code controlRevision} — it means command dispatch is
 * committed, not that the action has finished. CONFIRMATION_REQUIRED carries
 * the engine-stamped confirmation expiry (120s default).</p>
 */
public record ExecutionControlRequestResolvedPayload(
        UUID executionId,
        UUID dispatchId,
        UUID interactionId,
        UUID controlRequestId,
        long resolutionRevision,
        Status status,
        Instant expiresAt,
        String commandMessageId,
        Long controlRevision,
        ErrorCode errorCode) {

    /** section 22.7: proposal dispositions. */
    public enum Status { ACCEPTED, CONFIRMATION_REQUIRED, REJECTED, DECLINED, EXPIRED }

    /** section 22.7: the closed refusal-code catalogue. */
    public enum ErrorCode { FORBIDDEN, EXECUTION_TERMINAL, INVALID_INTERACTION, CONFIRMATION_EXPIRED }

    public ExecutionControlRequestResolvedPayload {
        if (executionId == null || dispatchId == null || interactionId == null
                || controlRequestId == null) {
            throw new IllegalArgumentException(
                    "execution.control.request.resolved requires executionId, dispatchId, "
                            + "interactionId, controlRequestId");
        }
        if (resolutionRevision < 1) {
            throw new IllegalArgumentException(
                    "resolutionRevision starts at 1 and increases per disposition");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (status == Status.ACCEPTED && commandMessageId == null) {
            throw new IllegalArgumentException(
                    "ACCEPTED includes the persisted commandMessageId (section 22.7)");
        }
        if (status == Status.CONFIRMATION_REQUIRED && expiresAt == null) {
            throw new IllegalArgumentException(
                    "CONFIRMATION_REQUIRED carries the confirmation expiry (section 22.7)");
        }
    }
}
