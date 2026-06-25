package ai.myrmec.engine.conversation;

import ai.myrmec.engine.conversation.stream.ConversationStreamBroker;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Emits engine-generated SYSTEM notices into a conversation (#86). The first
 * such notice is the "no agent online" message shown when a USER turn arrives
 * but no warm worker can be reserved: instead of silently dropping the turn the
 * engine persists a SYSTEM row and pushes it to live viewers so the user knows
 * their message was received and will be answered once an agent comes online.
 *
 * <p>Notice rows carry a {@code payload_json} marker so context assembly can
 * recognise and skip them — a "no agent online" line must never be shipped to
 * the agent that eventually picks the turn up.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationNoticeService {

    /** Marker kind stored in {@code payload_json} on a no-agent notice row. */
    public static final String NO_AGENT_NOTICE_KIND = "NO_AGENT_NOTICE";

    /** Serialised marker written to {@code payload_json}. */
    private static final String NO_AGENT_NOTICE_MARKER =
            "{\"kind\":\"" + NO_AGENT_NOTICE_KIND + "\"}";

    /** User-facing body of the no-agent notice. */
    private static final String NO_AGENT_NOTICE_MESSAGE =
            "No agent is currently online to handle your message. "
            + "We'll respond as soon as one becomes available.";

    private final ConversationService conversationService;
    private final ConversationMessageRepository messageRepository;
    private final ConversationStreamBroker conversationStreamBroker;
    private final ObjectMapper objectMapper;

    /**
     * Append (and broadcast) a one-time "no agent online" notice for a
     * conversation whose latest USER turn could not be dispatched. Idempotent
     * per unanswered turn: if the conversation's most recent row is already a
     * no-agent notice the call is a no-op, so repeated dispatch attempts (e.g.
     * the backlog drainer retrying) never spam the thread.
     */
    public void emitNoAgentNotice(UUID conversationId) {
        Optional<ConversationMessage> latest =
                messageRepository.findFirstByConversationIdOrderBySequenceNoDesc(conversationId);
        if (latest.isPresent() && isNoAgentNotice(latest.get())) {
            // The current unanswered turn already carries its notice.
            return;
        }

        ConversationMessage notice = conversationService.appendSystemNotice(
                conversationId, NO_AGENT_NOTICE_MESSAGE, NO_AGENT_NOTICE_MARKER);
        broadcast(notice);
        log.info("Emitted no-agent notice for conversation {} (seq {})",
                conversationId, notice.getSequenceNo());
    }

    /**
     * Whether a message is an engine no-agent notice. Cheap marker-substring
     * check — the marker is engine-controlled so a {@code contains} on the kind
     * is sufficient and avoids parsing JSON for every history row.
     */
    public boolean isNoAgentNotice(ConversationMessage message) {
        if (message == null || message.getRole() != ConversationMessage.Role.SYSTEM) {
            return false;
        }
        String json = message.getPayloadJson();
        return json != null && json.contains(NO_AGENT_NOTICE_KIND);
    }

    /**
     * Fan the persisted notice out to live viewers as a {@code history.message}
     * frame — the same envelope the user-WS handshake replays — so the existing
     * UI message-merge path renders it immediately with no client change.
     */
    private void broadcast(ConversationMessage notice) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("conversationId", notice.getConversationId().toString());
            payload.put("messageId", notice.getId().toString());
            payload.put("sequenceNo", notice.getSequenceNo());
            payload.put("role", ConversationMessage.Role.SYSTEM.name());
            payload.put("content", notice.getContent());
            if (notice.getCreatedAt() != null) {
                payload.put("createdAt", notice.getCreatedAt().toString());
            }

            Map<String, Object> envelope = new HashMap<>();
            envelope.put("type", "history.message");
            envelope.put("payload", payload);

            conversationStreamBroker.broadcast(
                    notice.getConversationId(), objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            // Persistence already succeeded; a broadcast failure just means the
            // notice surfaces on the viewer's next refetch instead of live.
            log.warn("Failed to broadcast no-agent notice for conversation {}: {}",
                    notice.getConversationId(), e.getMessage());
        }
    }
}
