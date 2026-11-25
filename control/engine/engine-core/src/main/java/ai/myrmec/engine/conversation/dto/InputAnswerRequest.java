// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.conversation.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Body for {@code POST /api/v1/conversations/{convId}/inputs/{messageId}/answer}.
 *
 * <p>{@code answer} is the ask_user resume union — {@code options} (one or more
 * option ids), {@code text} (typed text), or {@code dismissed} (the user clears
 * the question without answering). Matches the SDK's
 * {@code execution.start.resume.answer} shape verbatim.</p>
 */
public record InputAnswerRequest(
        @NotNull Answer answer
) {
    public record Answer(
            String kind,
            List<String> ids,
            String text
    ) {
    }
}
