package ai.myrmec.engine.conversation.dispatch;

import ai.myrmec.engine.conversation.ContextSummaryMarker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-replica record of the summarisation turns currently in flight (#8).
 *
 * <p>A summary turn is dispatched over the very same conversation-socket path
 * as a normal chat turn, so its eventual {@code message.complete} is
 * indistinguishable on the wire from an assistant reply. The dispatcher marks
 * the conversation here at summarise-dispatch time with the coverage the
 * summary will fold; the inbound completion handler consumes the mark to route
 * that completion into a {@link ai.myrmec.engine.conversation.ConversationMessage.Role#CONTEXT_SUMMARY}
 * row (with the right {@code coversUpToSequenceNo}) instead of an ASSISTANT
 * row, and crucially to <em>not</em> re-trigger summarisation (loop guard).</p>
 *
 * <p>State is node-local: the summary turn is bound to a worker that attaches
 * back to this replica, so its completion always lands here.</p>
 */
@Slf4j
@Component
public class SummaryInFlightRegistry {

    /** conversationId → the coverage the in-flight summary will record. */
    private final Map<UUID, ContextSummaryMarker> inFlight = new ConcurrentHashMap<>();

    /** True if a summary turn is already in flight for this conversation. */
    public boolean isInFlight(UUID conversationId) {
        return inFlight.containsKey(conversationId);
    }

    /**
     * Mark a summary turn as dispatched for a conversation. A second mark while
     * one is already in flight is refused (returns false) so two overlapping
     * summarise turns can't both write a CONTEXT_SUMMARY row.
     */
    public boolean mark(UUID conversationId, ContextSummaryMarker marker) {
        ContextSummaryMarker previous = inFlight.putIfAbsent(conversationId, marker);
        if (previous != null) {
            log.debug("Summary already in flight for conversation {} \u2014 refusing overlap", conversationId);
            return false;
        }
        return true;
    }

    /**
     * Remove and return the in-flight marker for a conversation, if any. Called
     * by the inbound completion handler to decide whether a completion is a
     * summary turn (present) or a normal chat turn (absent).
     */
    public Optional<ContextSummaryMarker> consume(UUID conversationId) {
        return Optional.ofNullable(inFlight.remove(conversationId));
    }

    /** Drop an in-flight mark without recording a summary (e.g. on cancel/teardown). */
    public void discard(UUID conversationId) {
        inFlight.remove(conversationId);
    }
}
