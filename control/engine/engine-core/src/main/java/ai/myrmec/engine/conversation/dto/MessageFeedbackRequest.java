package ai.myrmec.engine.conversation.dto;

import jakarta.validation.constraints.Size;

/**
 * Submit, change, or clear participant feedback on a single ASSISTANT
 * message.
 *
 * <p>A {@code null} or blank {@code rating} clears any existing feedback
 * (un-rates the message). A non-null rating must be {@code UP} or
 * {@code DOWN} (case-insensitive); anything else is rejected by the
 * service. The optional {@code reason} is a short free-text note that
 * accompanies the rating.</p>
 */
public record MessageFeedbackRequest(
        String rating,
        @Size(max = 2000) String reason
) {
}
