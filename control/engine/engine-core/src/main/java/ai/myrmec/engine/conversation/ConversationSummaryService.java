package ai.myrmec.engine.conversation;

import ai.myrmec.engine.conversation.dispatch.ConversationTurnDispatcher;
import ai.myrmec.engine.conversation.dispatch.SummaryInFlightRegistry;
import ai.myrmec.engine.setting.SystemSettingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Context-window summarisation policy (#8). Decides <em>when</em> a
 * conversation's older turns should be compacted and hands the actual
 * generation to {@link ConversationTurnDispatcher#dispatchSummary} as an
 * engine-orchestrated summary turn.
 *
 * <p>The engine owns no LLM client, so the summary is produced by the
 * conversation's own agent over the existing dispatch path; the completion is
 * routed back into a {@link ConversationMessage.Role#CONTEXT_SUMMARY} row by
 * the inbound handler. Summarisation runs off the turn critical path (triggered
 * after a normal assistant turn completes) and is strictly best-effort: when no
 * warm worker is free it is skipped and retried after the next turn.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationSummaryService {

    /** Setting key for the message-count threshold that triggers summarisation. */
    static final String TRIGGER_COUNT_KEY = "summary_trigger_message_count";

    /** Default trigger threshold when the setting is unset (2x the recent window). */
    static final long TRIGGER_COUNT_DEFAULT = 40L;

    private final ConversationService conversationService;
    private final SystemSettingService systemSettingService;
    private final ConversationTurnDispatcher dispatcher;
    private final SummaryInFlightRegistry summaryInFlightRegistry;
    private final ObjectMapper objectMapper;

    /**
     * Summarise the conversation's older turns when the un-summarised, active
     * message count has grown past the configured threshold. No-op when
     * summarisation is disabled (threshold &le; 0), already in flight, or the
     * overflow is still within the recent verbatim window.
     */
    public void summariseIfNeeded(UUID conversationId) {
        if (summaryInFlightRegistry.isInFlight(conversationId)) {
            return;
        }
        long threshold = systemSettingService.getInt(TRIGGER_COUNT_KEY, TRIGGER_COUNT_DEFAULT);
        if (threshold <= 0) {
            return; // summarisation disabled
        }

        Pending pending = collectUnsummarised(conversationId);
        if (pending == null || pending.unsummarised().size() <= threshold) {
            return; // still within budget (or nothing to fold)
        }

        // Keep the most-recent window verbatim; fold everything older than it.
        int foldCount = pending.unsummarised().size() - ConversationTurnDispatcher.HISTORY_LIMIT;
        if (foldCount <= 0) {
            return;
        }
        List<ConversationMessage> olderBlock =
                new ArrayList<>(pending.unsummarised().subList(0, foldCount));
        dispatch(conversationId, pending.previousSummaryContent(), olderBlock,
                ContextSummaryMarker.Trigger.AUTO);
    }

    /**
     * Explicit handoff trigger (#8 / UC-014). Folds <em>every</em> un-summarised
     * active turn into a fresh running summary right now, regardless of the
     * automatic threshold or the recent verbatim window — the caller is handing
     * the thread over to a fresh assistant and wants the whole conversation
     * compacted. No-op (returns {@code false}) when a summary is already in
     * flight or there is nothing new to fold.
     *
     * @return {@code true} if a summary turn was dispatched
     */
    public boolean summariseNow(UUID conversationId) {
        if (summaryInFlightRegistry.isInFlight(conversationId)) {
            return false;
        }
        Pending pending = collectUnsummarised(conversationId);
        if (pending == null || pending.unsummarised().isEmpty()) {
            return false;
        }
        return dispatch(conversationId, pending.previousSummaryContent(),
                new ArrayList<>(pending.unsummarised()),
                ContextSummaryMarker.Trigger.EXPLICIT);
    }

    /**
     * The active turns not yet folded by a summary, plus the carry-forward body
     * of the most-recent summary. Returns {@code null} when the conversation has
     * no active messages.
     */
    private Pending collectUnsummarised(UUID conversationId) {
        List<ConversationMessage> active = conversationService.listMessages(conversationId).stream()
                .filter(m -> !m.isSuperseded())
                .toList();
        if (active.isEmpty()) {
            return null;
        }

        ConversationMessage latestSummary = null;
        for (ConversationMessage m : active) {
            if (m.getRole() == ConversationMessage.Role.CONTEXT_SUMMARY
                    && (latestSummary == null || m.getSequenceNo() > latestSummary.getSequenceNo())) {
                latestSummary = m;
            }
        }
        long coverage = latestSummary == null ? Long.MIN_VALUE : coverageOf(latestSummary);
        String previousSummaryContent = latestSummary == null ? null : latestSummary.getContent();

        List<ConversationMessage> unsummarised = new ArrayList<>();
        for (ConversationMessage m : active) {
            if (m.getRole() == ConversationMessage.Role.CONTEXT_SUMMARY) {
                continue;
            }
            if (m.getSequenceNo() <= coverage) {
                continue;
            }
            unsummarised.add(m);
        }
        return new Pending(previousSummaryContent, unsummarised);
    }

    /**
     * Reserve the in-flight latch for {@code olderBlock} and hand it to the
     * dispatcher. Releases the latch when no warm worker could be reserved so a
     * later turn (or retry) picks the thread back up.
     *
     * @return {@code true} if a summary turn was dispatched
     */
    private boolean dispatch(UUID conversationId,
                             String previousSummaryContent,
                             List<ConversationMessage> olderBlock,
                             ContextSummaryMarker.Trigger trigger) {
        long coversFrom = olderBlock.get(0).getSequenceNo();
        long coversUpTo = olderBlock.get(olderBlock.size() - 1).getSequenceNo();
        ContextSummaryMarker marker =
                ContextSummaryMarker.of(coversFrom, coversUpTo, olderBlock.size(), trigger);
        if (!summaryInFlightRegistry.mark(conversationId, marker)) {
            return false; // a concurrent trigger won the race
        }
        boolean dispatched = dispatcher.dispatchSummary(
                conversationId, previousSummaryContent, olderBlock);
        if (!dispatched) {
            // No warm worker right now; drop the mark so a later turn retries.
            summaryInFlightRegistry.discard(conversationId);
            log.debug("Summary deferred for conv {} \u2014 no capacity", conversationId);
        }
        return dispatched;
    }

    /** The pending fold: the prior summary body to carry forward + un-folded turns. */
    private record Pending(String previousSummaryContent, List<ConversationMessage> unsummarised) {
    }

    /** Read the authoritative coverage bound from a summary row's marker. */
    private long coverageOf(ConversationMessage summary) {
        String json = summary.getPayloadJson();
        if (json != null && !json.isBlank()) {
            try {
                ContextSummaryMarker marker = objectMapper.readValue(json, ContextSummaryMarker.class);
                if (marker != null && marker.coversUpToSequenceNo() != null) {
                    return marker.coversUpToSequenceNo();
                }
            } catch (Exception e) {
                log.warn("Unparseable CONTEXT_SUMMARY marker on message {} (conv {}): {}",
                        summary.getId(), summary.getConversationId(), e.getMessage());
            }
        }
        return summary.getSequenceNo() - 1;
    }
}
