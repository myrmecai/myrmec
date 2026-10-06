// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

/**
 * section 22.6 execution.interaction.delta — the EPHEMERAL best-effort answer
 * fragment. {@code index} starts at 0 per interaction; gaps are tolerated,
 * buffers are bounded, no replay is promised, never acknowledged durably.
 */
public record ExecutionInteractionDeltaPayload(
        java.util.UUID executionId,
        java.util.UUID dispatchId,
        java.util.UUID interactionId,
        long index,
        String text) {

    public ExecutionInteractionDeltaPayload {
        if (executionId == null || dispatchId == null || interactionId == null) {
            throw new IllegalArgumentException(
                    "execution.interaction.delta requires executionId, dispatchId, interactionId");
        }
        if (index < 0) {
            throw new IllegalArgumentException("index starts at 0 per interaction");
        }
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("delta text is required and nonempty");
        }
    }
}