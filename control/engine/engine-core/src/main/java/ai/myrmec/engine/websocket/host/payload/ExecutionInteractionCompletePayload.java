// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * section 22.6 execution.interaction.complete — the durable outcome carrying
 * the complete answer and attributed chat usage. The complete record replaces
 * the provisional deltas rather than appending to them.
 *
 * <p>{@code usageStatus} is KNOWN or UNKNOWN; UNKNOWN is not zero and invokes
 * the existing fail-closed budget behavior. {@code controlRequestIds} lists
 * the proposals issued during the turn (§22.7).</p>
 */
public record ExecutionInteractionCompletePayload(
        UUID executionId,
        UUID dispatchId,
        UUID interactionId,
        long ordinal,
        Answer answer,
        Usage usage,
        UsageStatus usageStatus,
        List<UUID> controlRequestIds,
        Instant completedAt) {

    /** section 22.6: the complete answer block. */
    public record Answer(String text) {
        public Answer {
            if (text == null || text.isEmpty()) {
                throw new IllegalArgumentException("answer.text is required and nonempty");
            }
        }
    }

    /** section 22.6: attributed chat usage. Null tokens mean unknown. */
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

    public ExecutionInteractionCompletePayload {
        if (executionId == null || dispatchId == null || interactionId == null) {
            throw new IllegalArgumentException(
                    "execution.interaction.complete requires executionId, dispatchId, interactionId");
        }
        if (ordinal < 1) {
            throw new IllegalArgumentException("ordinal strictly increases (starts at 1)");
        }
        if (answer == null) {
            throw new IllegalArgumentException("answer is required");
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
        if (usageStatus == UsageStatus.KNOWN && usage == null) {
            throw new IllegalArgumentException(
                    "KNOWN usage requires the usage block (UNKNOWN is sent as null usage)");
        }
        controlRequestIds = List.copyOf(controlRequestIds);
    }
}