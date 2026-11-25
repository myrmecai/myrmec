package ai.myrmec.engine.conversation;

import ai.myrmec.engine.conversation.dispatch.ConversationTurnDispatcher;
import ai.myrmec.engine.conversation.dispatch.SummaryInFlightRegistry;
import ai.myrmec.engine.setting.SystemSettingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #8 — verifies the {@link ConversationSummaryService} trigger policy: it only
 * dispatches a summary turn once the un-summarised active message count grows
 * past the configured threshold, folds everything older than the verbatim
 * recent window, respects the in-flight latch + the disable switch, and carries
 * an earlier summary forward.
 *
 * <p>Pure Mockito (no Spring) — the service has no JPA/transactional behaviour
 * of its own; persistence is exercised by the controller tests.</p>
 */
class ConversationSummaryServiceTest {

    private ConversationService conversationService;
    private SystemSettingService systemSettingService;
    private ConversationTurnDispatcher dispatcher;
    private SummaryInFlightRegistry registry;

    private ConversationSummaryService service;

    private final UUID conversationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        conversationService = mock(ConversationService.class);
        systemSettingService = mock(SystemSettingService.class);
        dispatcher = mock(ConversationTurnDispatcher.class);
        registry = mock(SummaryInFlightRegistry.class);

        // Default: not in flight, default threshold passthrough, mark wins.
        when(registry.isInFlight(any())).thenReturn(false);
        when(registry.mark(any(), any())).thenReturn(true);
        when(systemSettingService.getInt(any(), anyLong()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(dispatcher.dispatchSummary(any(), any(), any())).thenReturn(true);

        service = new ConversationSummaryService(
                conversationService,
                systemSettingService,
                dispatcher,
                registry,
                new ObjectMapper());
    }

    @Test
    void noOpWhenAlreadyInFlight() {
        when(registry.isInFlight(conversationId)).thenReturn(true);

        service.summariseIfNeeded(conversationId);

        verify(conversationService, never()).listMessages(any());
        verify(dispatcher, never()).dispatchSummary(any(), any(), any());
    }

    @Test
    void noOpWhenDisabled() {
        when(systemSettingService.getInt(eq(ConversationSummaryService.TRIGGER_COUNT_KEY), anyLong()))
                .thenReturn(0L);

        service.summariseIfNeeded(conversationId);

        verify(dispatcher, never()).dispatchSummary(any(), any(), any());
    }

    @Test
    void noOpWhenWithinBudget() {
        // Threshold 5; only 5 active messages → not past threshold.
        when(systemSettingService.getInt(eq(ConversationSummaryService.TRIGGER_COUNT_KEY), anyLong()))
                .thenReturn(5L);
        when(conversationService.listMessages(conversationId))
                .thenReturn(buildMessages(5));

        service.summariseIfNeeded(conversationId);

        verify(dispatcher, never()).dispatchSummary(any(), any(), any());
        verify(registry, never()).mark(any(), any());
    }

    @Test
    void dispatchesAndFoldsOlderThanRecentWindow() {
        // Threshold low (2) but HISTORY_LIMIT keeps the recent window verbatim,
        // so only the overflow ahead of the window is folded.
        when(systemSettingService.getInt(eq(ConversationSummaryService.TRIGGER_COUNT_KEY), anyLong()))
                .thenReturn(2L);
        int total = ConversationTurnDispatcher.HISTORY_LIMIT + 3; // 3 to fold
        when(conversationService.listMessages(conversationId))
                .thenReturn(buildMessages(total));

        service.summariseIfNeeded(conversationId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConversationMessage>> olderCap =
                ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<ContextSummaryMarker> markerCap =
                ArgumentCaptor.forClass(ContextSummaryMarker.class);

        verify(registry).mark(eq(conversationId), markerCap.capture());
        verify(dispatcher).dispatchSummary(eq(conversationId), eq(null), olderCap.capture());

        List<ConversationMessage> older = olderCap.getValue();
        assertThat(older).hasSize(3);
        assertThat(older.get(0).getSequenceNo()).isZero();
        assertThat(older.get(2).getSequenceNo()).isEqualTo(2L);

        ContextSummaryMarker marker = markerCap.getValue();
        assertThat(marker.coversFromSequenceNo()).isZero();
        assertThat(marker.coversUpToSequenceNo()).isEqualTo(2L);
        assertThat(marker.summarizedMessageCount()).isEqualTo(3);
        // #8a — the audit trigger is stamped at dispatch; AUTO on the threshold path.
        assertThat(marker.trigger()).isEqualTo(ContextSummaryMarker.Trigger.AUTO);
        // model + tokens are unknown until completion.
        assertThat(marker.modelCode()).isNull();
        assertThat(marker.tokenCount()).isNull();
    }

    @Test
    void carriesPreviousSummaryAndCoverageForward() {
        when(systemSettingService.getInt(eq(ConversationSummaryService.TRIGGER_COUNT_KEY), anyLong()))
                .thenReturn(2L);

        // seq 0..4 folded by an existing summary (coversUpTo 4) at seq 5, then a
        // fresh run of messages large enough to overflow again.
        List<ConversationMessage> msgs = new ArrayList<>();
        for (long s = 0; s <= 4; s++) {
            msgs.add(newMessage(s, ConversationMessage.Role.USER, "old-" + s));
        }
        ConversationMessage summary = newMessage(5, ConversationMessage.Role.CONTEXT_SUMMARY, "earlier summary");
        summary.setPayloadJson("{\"kind\":\"CONTEXT_SUMMARY\",\"coversUpToSequenceNo\":4,\"summarizedMessageCount\":5}");
        msgs.add(summary);
        // Fresh post-summary turns: enough to overflow the verbatim window.
        int fresh = ConversationTurnDispatcher.HISTORY_LIMIT + 2;
        for (int i = 0; i < fresh; i++) {
            msgs.add(newMessage(6 + i, ConversationMessage.Role.USER, "new-" + i));
        }
        when(conversationService.listMessages(conversationId)).thenReturn(msgs);

        service.summariseIfNeeded(conversationId);

        // The earlier summary body is carried forward to the dispatcher, and no
        // message at or below the prior coverage (seq <= 4) is re-folded.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConversationMessage>> olderCap =
                ArgumentCaptor.forClass(List.class);
        verify(dispatcher).dispatchSummary(eq(conversationId), eq("earlier summary"), olderCap.capture());
        assertThat(olderCap.getValue()).allMatch(m -> m.getSequenceNo() > 4L);
        assertThat(olderCap.getValue()).allMatch(m -> m.getRole() != ConversationMessage.Role.CONTEXT_SUMMARY);
    }

    @Test
    void discardsMarkWhenNoCapacity() {
        when(systemSettingService.getInt(eq(ConversationSummaryService.TRIGGER_COUNT_KEY), anyLong()))
                .thenReturn(2L);
        when(conversationService.listMessages(conversationId))
                .thenReturn(buildMessages(ConversationTurnDispatcher.HISTORY_LIMIT + 3));
        when(dispatcher.dispatchSummary(any(), any(), any())).thenReturn(false);

        service.summariseIfNeeded(conversationId);

        verify(registry).mark(eq(conversationId), any());
        verify(registry).discard(conversationId);
    }

    @Test
    void summariseNowFoldsEverythingIgnoringThresholdAndWindow() {
        // Only 4 messages — well under any threshold and under HISTORY_LIMIT —
        // yet an explicit handoff folds them all.
        when(conversationService.listMessages(conversationId))
                .thenReturn(buildMessages(4));

        boolean dispatched = service.summariseNow(conversationId);

        assertThat(dispatched).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConversationMessage>> olderCap =
                ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<ContextSummaryMarker> markerCap =
                ArgumentCaptor.forClass(ContextSummaryMarker.class);
        verify(registry).mark(eq(conversationId), markerCap.capture());
        verify(dispatcher).dispatchSummary(eq(conversationId), eq(null), olderCap.capture());
        assertThat(olderCap.getValue()).hasSize(4);
        // #8a — the explicit handoff path stamps EXPLICIT and the full range.
        ContextSummaryMarker marker = markerCap.getValue();
        assertThat(marker.trigger()).isEqualTo(ContextSummaryMarker.Trigger.EXPLICIT);
        assertThat(marker.coversFromSequenceNo()).isZero();
        assertThat(marker.coversUpToSequenceNo()).isEqualTo(3L);
        assertThat(marker.summarizedMessageCount()).isEqualTo(4);
        // Threshold setting is never consulted on the explicit path.
        verify(systemSettingService, never()).getInt(any(), anyLong());
    }

    @Test
    void summariseNowNoOpWhenInFlight() {
        when(registry.isInFlight(conversationId)).thenReturn(true);

        assertThat(service.summariseNow(conversationId)).isFalse();
        verify(dispatcher, never()).dispatchSummary(any(), any(), any());
    }

    @Test
    void summariseNowNoOpWhenNothingToFold() {
        when(conversationService.listMessages(conversationId))
                .thenReturn(List.of());

        assertThat(service.summariseNow(conversationId)).isFalse();
        verify(dispatcher, never()).dispatchSummary(any(), any(), any());
    }

    // -- helpers ----------------------------------------------------------

    private List<ConversationMessage> buildMessages(int count) {
        List<ConversationMessage> msgs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ConversationMessage.Role role = (i % 2 == 0)
                    ? ConversationMessage.Role.USER
                    : ConversationMessage.Role.ASSISTANT;
            msgs.add(newMessage(i, role, "m-" + i));
        }
        return msgs;
    }

    private ConversationMessage newMessage(long seq, ConversationMessage.Role role, String content) {
        ConversationMessage m = new ConversationMessage();
        m.setId(UUID.randomUUID());
        m.setConversationId(conversationId);
        m.setSequenceNo(seq);
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(Instant.now());
        return m;
    }
}
