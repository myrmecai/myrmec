package ai.myrmec.engine.websocket.message.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload for {@code approval.request} (Agent → Engine, Phase 7c).
 *
 * <p>The agent generates {@code clientRequestId} client-side so it can
 * key the pending-future on it; the engine echoes it back verbatim in
 * the eventual {@code approval.decision} frame. The engine still
 * persists its own canonical {@code message_id} for the
 * {@code APPROVAL_REQUEST} row — both ids appear on the broadcast
 * envelope so UI viewers and the originating agent stay in sync.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApprovalRequestPayload {

    /** Conversation the approval belongs to. */
    private UUID conversationId;

    /**
     * Agent-supplied correlation id. Echoed back in
     * {@link ApprovalDecisionPayload#clientRequestId}.
     */
    private UUID clientRequestId;

    /**
     * Engine-assigned id of the persisted {@code APPROVAL_REQUEST}
     * message row. Null on inbound frames; populated by the engine on
     * the outbound broadcast / ack envelope.
     */
    private UUID messageId;

    /** Human-readable summary (rendered in the approval card). */
    private String content;

    /**
     * Free-form JSON the UI uses to decide which renderer (diff, SQL,
     * shell, plain) to show. The engine treats this as an opaque
     * string and persists it verbatim.
     */
    private String payloadJson;

    /** Wall-clock deadline; null = no expiry (admin will sweep). */
    private Instant expiresAt;

    /** Engine-assigned sequence number of the persisted row. */
    private Long sequenceNo;
}
