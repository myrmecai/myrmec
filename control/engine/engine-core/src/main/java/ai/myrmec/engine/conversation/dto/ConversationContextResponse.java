// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.conversation.dto;

/**
 * Per-turn context-window read-model (UC-013 header "context bagel").
 *
 * <p>{@code contextTokens} is the token count of the conversation's latest
 * ASSISTANT turn (an approximation of the current context size — the engine
 * does not yet persist per-turn prompt-token usage separately, so the most
 * recent turn's total tokens stand in). {@code null} until the first
 * ASSISTANT turn has reported usage.</p>
 *
 * <p>{@code maxContextTokens} is the serving model's context-window ceiling
 * (from {@code models.context_window_tokens}); {@code null} when the model is
 * unknown or does not declare a window.</p>
 *
 * <p>TODO(context): replace the turn-total approximation with a true
 * prompt-token read once per-turn usage is captured separately from the
 * completion total.</p>
 */
public record ConversationContextResponse(
        Integer contextTokens,
        Integer maxContextTokens) {

    public static ConversationContextResponse of(Integer contextTokens, Integer maxContextTokens) {
        return new ConversationContextResponse(contextTokens, maxContextTokens);
    }
}
