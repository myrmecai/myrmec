// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.conversation;

import ai.myrmec.engine.conversation.event.ConversationArchivedEvent;
import ai.myrmec.engine.conversation.event.ConversationClosedEvent;
import ai.myrmec.engine.conversation.event.IdleSessionExpiredEvent;
import ai.myrmec.engine.conversation.stream.ConversationStreamBroker;
import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.websocket.host.HostControlWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link ConversationIdleListener} (2026-09-21
 * close/archive lifecycle design section 5): the idle event pushes
 * session.close to the host and a conversation.state IDLE frame to the SSE
 * viewers; the close/archive events push session.close per host-bound
 * session without broadcasting (the controller owns that frame). Pushes are
 * best-effort - a failing push never propagates.
 */
class ConversationIdleListenerTest {

    private static final ObjectMapper JSON =
            new ObjectMapper().registerModule(new JavaTimeModule());

    private HostControlWebSocketHandler hostHandler;
    private ConversationStreamBroker broker;
    private SessionRepository sessionRepository;

    private ConversationIdleListener listener;

    private final UUID sessionId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private final UUID hostInstanceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        hostHandler = mock(HostControlWebSocketHandler.class);
        broker = mock(ConversationStreamBroker.class);
        sessionRepository = mock(SessionRepository.class);
        listener = new ConversationIdleListener(
                hostHandler, broker, JSON, sessionRepository);
    }

    @Test
    void idleEventPushesHostCloseAndBroadcastsIdleFrame() throws Exception {
        listener.on(new IdleSessionExpiredEvent(sessionId, conversationId, hostInstanceId));

        verify(hostHandler).sendSessionClose(sessionId, "IDLE_LEASE_EXPIRED");

        ArgumentCaptor<String> frameCap = ArgumentCaptor.forClass(String.class);
        verify(broker).broadcast(eq(conversationId), frameCap.capture());
        JsonNode envelope = JSON.readTree(frameCap.getValue());
        assertThat(envelope.path("type").asText()).isEqualTo("conversation.state");
        JsonNode payload = envelope.path("payload");
        assertThat(payload.path("conversationId").asText()).isEqualTo(conversationId.toString());
        assertThat(payload.path("state").asText()).isEqualTo("IDLE");
        assertThat(payload.path("reason").asText()).isEqualTo("IDLE_LEASE_EXPIRED");
        assertThat(payload.path("at").isNull()).isFalse();
    }

    @Test
    void idleEventStillBroadcastsWhenHostPushFails() {
        doThrow(new RuntimeException("socket gone"))
                .when(hostHandler).sendSessionClose(any(UUID.class), anyString());

        assertThatCode(() -> listener.on(
                new IdleSessionExpiredEvent(sessionId, conversationId, hostInstanceId)))
                .doesNotThrowAnyException();

        verify(broker).broadcast(eq(conversationId), anyString());
    }

    @Test
    void closedEventPushesHostClosePerBoundSessionOnly() {
        Session bound = session(UUID.randomUUID(), hostInstanceId);
        Session hostless = session(UUID.randomUUID(), null);
        when(sessionRepository.findByRefIdAndServiceType(conversationId, "CONVERSATION"))
                .thenReturn(List.of(bound, hostless));

        listener.on(new ConversationClosedEvent(conversationId, "USER_ENDED"));

        verify(hostHandler, times(1)).sendSessionClose(bound.getId(), "USER_ENDED");
        verify(hostHandler, never()).sendSessionClose(eq(hostless.getId()), anyString());
        // The conversation.state frame is the controller's push - not the listener's.
        verify(broker, never()).broadcast(any(UUID.class), anyString());
    }

    @Test
    void archivedEventPushesHostCloseWithArchiveReason() {
        Session bound = session(UUID.randomUUID(), hostInstanceId);
        when(sessionRepository.findByRefIdAndServiceType(conversationId, "CONVERSATION"))
                .thenReturn(List.of(bound));

        listener.on(new ConversationArchivedEvent(conversationId, "USER_ARCHIVED"));

        verify(hostHandler).sendSessionClose(bound.getId(), "USER_ARCHIVED");
        verify(broker, never()).broadcast(any(UUID.class), anyString());
    }

    @Test
    void closedEventWithNoSessionsPushesNothing() {
        when(sessionRepository.findByRefIdAndServiceType(conversationId, "CONVERSATION"))
                .thenReturn(List.of());

        listener.on(new ConversationClosedEvent(conversationId, "USER_ENDED"));

        verify(hostHandler, never()).sendSessionClose(any(UUID.class), anyString());
    }

    private Session session(UUID id, UUID hostId) {
        Session s = new Session();
        s.setId(id);
        s.setServiceType("CONVERSATION");
        s.setRefId(conversationId);
        s.setHostInstanceId(hostId);
        return s;
    }
}