// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine._protocol.payload;

import java.time.Instant;
import java.util.List;

/**
 * execution.input.requested (§22.6) — the ask_user interrupt: the agent paused
 * awaiting a human answer. Mirrors the SDK's {@code executionInputRequestedPayloadSchema}.
 *
 * <p>{@code digest} is a SHA-256 over the question payload, binding the resume
 * answer to exactly the question the human saw (same rule as approvals).</p>
 */
public record ExecutionInputRequestedPayload(
        String question,
        List<Option> options,
        Boolean multiple,
        String agentName,
        String digest,
        Instant expiresAt) {

    public record Option(
            String id,
            String label,
            String description,
            Boolean recommended) {}
}
