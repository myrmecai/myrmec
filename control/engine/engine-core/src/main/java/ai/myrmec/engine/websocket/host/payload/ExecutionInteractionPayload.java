// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

import java.time.Instant;
import java.util.UUID;

/**
 * section 22.6 execution.interaction — the engine's durable command carrying
 * ONE authorized user message to the session's embedded interactive loop.
 *
 * <p>{@code ordinal} strictly increases per execution under an engine row
 * lock. {@code actorUserId} is engine-stamped for audit, never worker
 * authority. The response deadline is
 * min(acceptance + session response timeout, execution deadline).</p>
 */
public record ExecutionInteractionPayload(
        UUID executionId,
        UUID dispatchId,
        UUID interactionId,
        long ordinal,
        UUID actorUserId,
        Message message,
        Instant acceptedAt,
        Instant responseDeadline) {

    /** section 22.6: the user message block. */
    public record Message(String text) {
        public Message {
            if (text == null || text.isEmpty()) {
                throw new IllegalArgumentException(
                        "message.text is required and nonempty (section 22.6)");
            }
        }
    }

    public ExecutionInteractionPayload {
        if (executionId == null || dispatchId == null || interactionId == null) {
            throw new IllegalArgumentException(
                    "execution.interaction requires executionId, dispatchId, interactionId");
        }
        if (ordinal < 1) {
            throw new IllegalArgumentException("ordinal strictly increases (starts at 1)");
        }
        if (actorUserId == null) {
            throw new IllegalArgumentException(
                    "actorUserId is engine-stamped for audit (section 22.6)");
        }
        if (message == null) {
            throw new IllegalArgumentException("message is required");
        }
        if (acceptedAt == null || responseDeadline == null) {
            throw new IllegalArgumentException("acceptedAt and responseDeadline are required");
        }
    }
}