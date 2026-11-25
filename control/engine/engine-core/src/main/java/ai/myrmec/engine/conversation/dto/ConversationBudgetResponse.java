// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.conversation.dto;

/**
 * Dummy per-conversation budget read-model (UC-013 header "budget bagel").
 *
 * <p>TODO(budget): this is a placeholder that returns zeros until the budget
 * read-model is designed. The real implementation must resolve the
 * conversation's effective quota (per-user or shared per-assistant ceiling),
 * the caller's own spend in cents (COST_USD_CENTS convention), the shared-pool
 * usage, and the {@code atRisk}/{@code exceeded} flags (>= 80% / >= 100% of
 * limit). See {@code quota-budget-ui-design.md} §22 for the intended shape.
 */
public record ConversationBudgetResponse(
        String mode,            // "PER_USER" | "PER_ASSISTANT" (dummy: PER_USER)
        String resourceType,    // "COST_USD_CENTS" | "TOKENS"
        String currency,
        String period,
        String enforcementMode,
        boolean paused,
        long limitCents,
        long myUsageCents,
        Long totalUsageCents,
        Long othersUsageCents,
        boolean atRisk,
        boolean exceeded) {

    /** A zero-valued placeholder until the budget read-model lands. */
    public static ConversationBudgetResponse dummy() {
        return new ConversationBudgetResponse(
                "PER_USER", "COST_USD_CENTS", "USD", "MONTHLY",
                "HARD", false, 0L, 0L, null, null, false, false);
    }
}
