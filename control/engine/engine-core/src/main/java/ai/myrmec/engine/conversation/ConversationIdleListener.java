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
import ai.myrmec.engine.websocket.message.MessageType;
import ai.myrmec.engine.websocket.message.WebSocketMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Fans conversation lifecycle state changes out to the push surfaces the
 * committing services cannot reach themselves (2026-09-21 close/archive
 * lifecycle design section 5).
 *
 * <p>The allocation sweep and ConversationService cannot inject
 * {@link HostControlWebSocketHandler} (bean cycle: the handler injects
 * ConversationService), so they publish events and this leaf listener owns
 * the pushes:</p>
 *
 * <ul>
 *   <li>{@link IdleSessionExpiredEvent} - the idle-lease sweep closed a
 *       CONVERSATION session; push session.close to the serving host and a
 *       conversation.state IDLE frame to the SSE viewers.</li>
 *   <li>{@link ConversationClosedEvent} / {@link ConversationArchivedEvent} -
 *       a lifecycle endpoint tore the conversation's sessions down; push
 *       session.close to every session's host. The SSE conversation.state
 *       frame for close/archive is broadcast by ConversationController
 *       (the push owner per design 5.1), not here, so viewers get exactly
 *       one frame per transition.</li>
 * </ul>
 *
 * <p>Every push is best-effort: a failure is logged and never rethrown -
 * the conversation state change is already durably committed.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConversationIdleListener {

    private final HostControlWebSocketHandler hostControlWebSocketHandler;
    private final ConversationStreamBroker streamBroker;
    private final ObjectMapper objectMapper;
    private final SessionRepository sessionRepository;

    /**
     * Idle-lease expiry (design 5.3): the sweep closed an idle CONVERSATION
     * session - notify the serving host and the SSE viewers. Runs on commit
     * of the publishing transaction; with no active transaction (the
     * scheduled sweep self-invokes the sweep body without one) it falls back
     * to immediate execution so the scheduled path signals too.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(IdleSessionExpiredEvent event) {
        try {
            hostControlWebSocketHandler.sendSessionClose(event.sessionId(), "IDLE_LEASE_EXPIRED");
        } catch (Exception e) {
            log.warn("Host session.close push for idle session {} failed: {}",
                    event.sessionId(), e.getMessage());
        }
        broadcastState(event.conversationId(), "IDLE", "IDLE_LEASE_EXPIRED");
    }

    /** Host push for an owner close (design 5.2). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(ConversationClosedEvent event) {
        pushSessionCloses(event.conversationId(), event.reason());
    }

    /** Host push for an owner archive (design 5.2). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(ConversationArchivedEvent event) {
        pushSessionCloses(event.conversationId(), event.reason());
    }

    /**
     * Push session.close to the host of every CONVERSATION session row of
     * the conversation that carries a live host instance (design 5.2), so a
     * connected host tears down its local session state. Stale duplicate
     * rows from crashed reconnects are pushed too - the teardown on the host
     * is idempotent.
     */
    private void pushSessionCloses(UUID conversationId, String reason) {
        for (Session session : sessionRepository.findByRefIdAndServiceType(
                conversationId, "CONVERSATION")) {
            if (session.getHostInstanceId() == null) {
                continue;
            }
            try {
                hostControlWebSocketHandler.sendSessionClose(session.getId(), reason);
            } catch (Exception e) {
                log.warn("Host session.close push for session {} on conversation {} failed: {}",
                        session.getId(), conversationId, e.getMessage());
            }
        }
    }

    /**
     * SSE conversation.state frame to the conversation's viewers - same
     * broadcast pattern as ApprovalExpirySweeper's approval.decision frame.
     */
    private void broadcastState(UUID conversationId, String state, String reason) {
        try {
            Map<String, Object> payload = Map.of(
                    "conversationId", conversationId.toString(),
                    "state", state,
                    "reason", reason,
                    "at", Instant.now().toString());
            String json = objectMapper.writeValueAsString(
                    WebSocketMessage.of(MessageType.CONVERSATION_STATE, payload));
            streamBroker.broadcast(conversationId, json);
        } catch (Exception e) {
            log.warn("conversation.state broadcast for conversation {} failed: {}",
                    conversationId, e.getMessage());
        }
    }
}