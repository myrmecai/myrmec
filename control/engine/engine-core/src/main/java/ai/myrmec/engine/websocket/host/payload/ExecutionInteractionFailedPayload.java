// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * section 22.6 execution.interaction.failed — the durable interaction-only
 * failure and partial usage. Interaction failure alone does not fail the
 * attempt, except the existing shared-budget/policy rules.
 *
 * <p>Error codes are the section 22.6 catalogue. Partial known usage is
 * always reported; {@code usageStatus} UNKNOWN invokes the existing
 * fail-closed budget behavior.</p>
 */
public record ExecutionInteractionFailedPayload(
        UUID executionId,
        UUID dispatchId,
        UUID interactionId,
        long ordinal,
        Error error,
        Usage usage,
        UsageStatus usageStatus,
        List<UUID> controlRequestIds,
        Instant completedAt) {

    /** section 22.6: the closed error-code catalogue. */
    public enum ErrorCode {
        INTERACTION_TIMEOUT, MODEL_ERROR, OUTPUT_LIMIT_EXCEEDED,
        TOKEN_USAGE_UNAVAILABLE, EXECUTION_TERMINAL, EXECUTION_CANCELLED,
        HOST_STATE_LOST, CAPTURE_BLOCKED
    }

    /** section 22.6: the failure detail block. */
    public record Error(ErrorCode errorCode, String message, boolean retryable) {
        public Error {
            if (errorCode == null) {
                throw new IllegalArgumentException("error.errorCode is required");
            }
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("error.message is required");
            }
        }
    }

    /** section 22.6: partial known usage; null tokens mean unknown. */
    public record Usage(Integer inputTokens, Integer outputTokens, String modelId) {
        public Usage {
            if (inputTokens != null && inputTokens < 0) {
                throw new IllegalArgumentException("inputTokens must be >= 0");
            }
            if (outputTokens != null && outputTokens < 0) {
                throw new IllegalArgumentException("outputTokens must be >= 0");
            }
        }
    }

    /** section 22.6: KNOWN or UNKNOWN — UNKNOWN is explicit, never zero. */
    public enum UsageStatus { KNOWN, UNKNOWN }

    public ExecutionInteractionFailedPayload {
        if (executionId == null || dispatchId == null || interactionId == null) {
            throw new IllegalArgumentException(
                    "execution.interaction.failed requires executionId, dispatchId, interactionId");
        }
        if (ordinal < 1) {
            throw new IllegalArgumentException("ordinal strictly increases (starts at 1)");
        }
        if (error == null) {
            throw new IllegalArgumentException("error is required");
        }
        if (usageStatus == null) {
            throw new IllegalArgumentException("usageStatus is required");
        }
        if (controlRequestIds == null) {
            throw new IllegalArgumentException("controlRequestIds is required (empty when none)");
        }
        if (completedAt == null) {
            throw new IllegalArgumentException("completedAt is required");
        }
        controlRequestIds = List.copyOf(controlRequestIds);
    }
}