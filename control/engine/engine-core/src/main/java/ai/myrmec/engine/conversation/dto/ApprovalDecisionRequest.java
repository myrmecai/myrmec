package ai.myrmec.engine.conversation.dto;

import ai.myrmec.engine.conversation.ConversationMessage;
import jakarta.validation.constraints.NotNull;

/**
 * Body for {@code POST /api/v1/conversations/{convId}/approvals/{messageId}}.
 *
 * <p>{@code decision} MUST be {@code APPROVED} or {@code REJECTED};
 * other values raise 400. {@code comment} is optional free-form text
 * stored on the paired {@code APPROVAL_RESPONSE} row.</p>
 */
public record ApprovalDecisionRequest(
        @NotNull ConversationMessage.ApprovalStatus decision,
        String comment
) { }
