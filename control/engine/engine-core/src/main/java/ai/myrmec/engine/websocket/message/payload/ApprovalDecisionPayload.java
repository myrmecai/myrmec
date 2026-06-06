package ai.myrmec.engine.websocket.message.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Payload for {@code approval.decision} (Engine → Agent, Phase 7c).
 *
 * <p>Sent after a human submits an approve / reject decision through
 * the REST surface (or after the scheduled sweeper marks the request
 * expired in Phase 7e). {@code clientRequestId} echoes the value the
 * agent supplied on the originating {@code approval.request} so the
 * agent SDK can resolve the pending future.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApprovalDecisionPayload {

    /** Conversation the decision belongs to. */
    private UUID conversationId;

    /** Engine-side id of the original {@code APPROVAL_REQUEST} row. */
    private UUID requestMessageId;

    /**
     * Agent-supplied correlation id from the original
     * {@code approval.request}. Allows the SDK to wake the right
     * blocked caller when several approvals are in flight.
     */
    private UUID clientRequestId;

    /** APPROVED / REJECTED / EXPIRED. */
    private String decision;

    /** Optional human comment captured at decision time. */
    private String comment;

    /** Engine-side id of the persisted {@code APPROVAL_RESPONSE} row, if any. */
    private UUID responseMessageId;

    /** UUID of the user who decided (null for EXPIRED system decisions). */
    private UUID approverUserId;
}
