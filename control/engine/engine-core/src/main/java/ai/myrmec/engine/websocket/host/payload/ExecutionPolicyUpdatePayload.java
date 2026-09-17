// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

import java.util.UUID;

/**
 * execution.policy.update (§8.7) — engine→host tighten-only limits update.
 *
 * <p>{@code usage} is the engine's durably accounted value derived from
 * accepted usage events (NOT a host capacity counter). The host compares it
 * with its local monotonic accounting and rejects an update that would roll
 * either value backward. {@code allowance} may only preserve or tighten the
 * current limit.</p>
 *
 * <p>Engine→host only: the same frame arriving from a host is an
 * INVALID_MESSAGE protocol violation (§4.3 arm catalogue).</p>
 */
public record ExecutionPolicyUpdatePayload(
        UUID executionId,
        UUID dispatchId,   // orchestration dispatch identity; null for conversations
        Usage usage,
        Allowance allowance) {

    /** §8.7: the engine's durably accounted usage. */
    public record Usage(long orchestrationFunctionCalls, long totalTokens) {}

    /** §8.7: tighten-only allowance (null = no token limit on the host). */
    public record Allowance(Long maxTokens) {}
}