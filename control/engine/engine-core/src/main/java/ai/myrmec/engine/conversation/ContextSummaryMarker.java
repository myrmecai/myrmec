package ai.myrmec.engine.conversation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Marker persisted in {@link ConversationMessage#getPayloadJson()} on a
 * {@link ConversationMessage.Role#CONTEXT_SUMMARY} row (#8). It pins exactly
 * which prior turns the summary folded so context assembly can drop them
 * deterministically, and doubles as the #8a transparency/audit record: an
 * AUDITOR can reconstruct, from the durable transcript alone, why each
 * summary fired ({@link #trigger}), which model produced it
 * ({@link #modelCode}), how large it was ({@link #tokenCount}), and exactly
 * which turns it replaced ({@link #coversFromSequenceNo}..{@link #coversUpToSequenceNo}).
 *
 * <p>{@code coversUpToSequenceNo} is authoritative — summarisation runs off
 * the turn critical path, so newer turns may have been appended after the
 * summarised block was sampled. Relying on the summary row's own sequence
 * number would wrongly evict those newer turns; the explicit coverage bound
 * avoids that.</p>
 *
 * <p>Coverage + trigger are known at dispatch time; the model and token
 * counts are only known once the summary turn completes, so they are filled
 * in via {@link #withCompletion(String, Integer)} at persist time. Unknown
 * fields deserialize to {@code null} (older rows predating #8a), so the
 * audit fields are strictly additive.</p>
 *
 * @param kind                    discriminator, always {@code "CONTEXT_SUMMARY"}
 * @param coversFromSequenceNo    lowest {@code sequence_no} folded into this
 *                                summary (inclusive); {@code null} on legacy rows
 * @param coversUpToSequenceNo    highest {@code sequence_no} folded into this
 *                                summary; messages at or below it are dropped
 *                                from the window
 * @param summarizedMessageCount  how many messages were compacted (for the
 *                                #8a transparency marker)
 * @param trigger                 why the summary fired ({@link Trigger}); {@code null}
 *                                on legacy rows
 * @param modelCode               model that produced the summary; {@code null} until
 *                                completion (or if the completion omitted it)
 * @param tokenCount              token count the summary turn reported; {@code null}
 *                                until completion
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContextSummaryMarker(
        String kind,
        Long coversFromSequenceNo,
        Long coversUpToSequenceNo,
        Integer summarizedMessageCount,
        Trigger trigger,
        String modelCode,
        Integer tokenCount) {

    /** Discriminator value stored in {@link #kind}. */
    public static final String KIND = "CONTEXT_SUMMARY";

    /** Why a summary generation fired — part of the #8a audit record. */
    public enum Trigger {
        /** Automatic threshold breach off the turn critical path (#8). */
        AUTO,
        /** Explicit user/handoff request via {@code summariseNow} (#8 / UC-014). */
        EXPLICIT
    }

    /**
     * Dispatch-time marker: coverage range + trigger are known now; the model
     * and token counts are filled in later via {@link #withCompletion}.
     */
    public static ContextSummaryMarker of(
            long coversFromSequenceNo,
            long coversUpToSequenceNo,
            int summarizedMessageCount,
            Trigger trigger) {
        return new ContextSummaryMarker(
                KIND, coversFromSequenceNo, coversUpToSequenceNo,
                summarizedMessageCount, trigger, null, null);
    }

    /**
     * Completion-time copy enriched with the model + token counts the summary
     * turn reported, ready to persist as the durable audit record.
     */
    public ContextSummaryMarker withCompletion(String modelCode, Integer tokenCount) {
        return new ContextSummaryMarker(
                kind, coversFromSequenceNo, coversUpToSequenceNo,
                summarizedMessageCount, trigger, modelCode, tokenCount);
    }
}
