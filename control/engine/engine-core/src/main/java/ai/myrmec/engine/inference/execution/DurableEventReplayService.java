// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution;

import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.node.HostFrameRelayService;
import ai.myrmec.engine.websocket.host.ChannelConnectionRegistry;
import ai.myrmec.engine.websocket.host.HostProtocol;
import ai.myrmec.engine.websocket.host.HostProtocolEnvelope;
import ai.myrmec.engine.websocket.host.payload.ProtocolErrorPayload;
import ai.myrmec.engine.workflow.ExecutionEvent;
import ai.myrmec.engine.workflow.ExecutionEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * §12.3/§13 (A2) durable-event replay: given a session and a cursor
 * ({@code resumeFromSequence} — the host's last contiguous envelope
 * sequence), resend the engine's retained durable {@code execution.event}
 * frames with a higher sequence, in order, on the session's current
 * transport (channel-first, node-aware). The events are the §8.4 rows the
 * engine durably recorded while the socket was down (or before the host
 * advanced its cursor) — Wave 3's accepted-but-not-yet-drained
 * {@code resumeFromSequence} gets its drain here.
 *
 * <p>Idempotent by construction: events already acknowledged by the host
 * have sequence ≤ its cursor and are never resent; the receiver-side
 * ingestion dedups a genuine replay by {@code source_event_id}. Ephemeral
 * deltas are NEVER replayed (§12: at-most-once, no gap-filling).</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DurableEventReplayService {

    private final SessionRepository sessionRepository;
    private final SessionExecutionRepository executionRepository;
    private final ExecutionEventRepository eventRepository;
    private final HostFrameRelayService frameRelayService;
    private final ChannelConnectionRegistry channelRegistry;
    private final ObjectMapper objectMapper;

    /**
     * Replay one session's durable events after {@code fromSequence}.
     *
     * @return the number of frames sent
     */
    public int replay(UUID sessionId, long fromSequence) {
        Session session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            log.debug("Replay skipped — session {} unknown", sessionId);
            return 0;
        }
        if (channelRegistry.getChannel(sessionId).isEmpty()) {
            log.debug("Replay for session {} deferred — no dedicated channel bound "
                    + "(replay rides the resumed control socket or a re-opened "
                    + "channel; §7.5)", sessionId);
            return 0;
        }

        List<ExecutionEvent> events = eventRepository.findAll().stream()
                .filter(e -> e.getSequenceNumber() != null
                        && e.getSequenceNumber() > fromSequence
                        && sameSession(session, e))
                .sorted(Comparator.comparing(ExecutionEvent::getSequenceNumber))
                .toList();

        // §12.3/§14 REPLAY_WINDOW_EXPIRED: when the host's cursor predates the
        // retained window the earliest retained row leaves a gap (cursor+1 is
        // gone) — the ENGINE reports the gap (retryable=false-by-error-table
        // is "continue with recorded gap") and continues from the oldest
        // retained event. Not an INVALID/UNSUPPORTED error: a notice that the
        // replay is lossy at the head.
        long lowestRetained = events.stream()
                .map(ExecutionEvent::getSequenceNumber)
                .map(Long::longValue)
                .findFirst()
                .orElse(-1L);
        if (!events.isEmpty() && lowestRetained > fromSequence + 1) {
            sendReplayWindowExpired(session, fromSequence, lowestRetained);
        }

        int sent = 0;
        for (ExecutionEvent event : events) {
            if (replayEvent(session, event)) {
                sent++;
            }
        }
        if (!events.isEmpty()) {
            log.info("Replayed {} durable event(s) for session {} after sequence {}{}",
                    sent, sessionId, fromSequence,
                    lowestRetained > fromSequence + 1
                            ? " (replay window expired — gap from " + (fromSequence + 1)
                                + " to " + (lowestRetained - 1) + ")"
                            : "");
        }
        return sent;
    }

    /**
     * §12.3: report the retention gap on the session's transport with the
     * handler's protocol.error wire shape — code {@code REPLAY_WINDOW_EXPIRED},
     * scope SESSION, and the replay CONTINUES from the earliest retained row
     * (the "continue with recorded gap" semantics; the gap is observability,
     * not an execution failure). The frame rides the relay like any other
     * engine→host frame (channel-first, node-aware).
     */
    private void sendReplayWindowExpired(Session session, long cursor, long lowestRetained) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("requestedFromSequence", cursor);
        details.put("lowestRetainedSequence", lowestRetained);
        HostProtocolEnvelope envelope = HostProtocolEnvelope.reply(
                HostProtocol.PROTOCOL_ERROR, null,
                new ProtocolErrorPayload(HostProtocol.REPLAY_WINDOW_EXPIRED,
                        "Durable-event replay requested before the retention window — "
                                + "continuing from the earliest retained event",
                        true, null, "SESSION", details),
                objectMapper);
        envelope.setSessionId(session.getId());
        try {
            boolean sent = frameRelayService.send(session.getId(), session.getHostInstanceId(),
                    objectMapper.writeValueAsString(envelope));
            log.info("REPLAY_WINDOW_EXPIRED notice for session {} (cursor {}, lowest retained {}) — {}",
                    session.getId(), cursor, lowestRetained, sent ? "sent" : "undeliverable");
        } catch (Exception e) {
            log.warn("Failed sending REPLAY_WINDOW_EXPIRED notice for session {}: {}",
                    session.getId(), e.getMessage());
        }
    }

    /**
     * Re-serialize one durable event as an §8.4 {@code execution.event}
     * envelope on the session's socket. The envelope reuses the persisted
     * identity: {@code source_event_id} as the §8.4 {@code eventId} and the
     * row's {@code sequence_number} as the envelope {@code sequence}, so a
     * replayed frame is indistinguishable from the original to the host and
     * receiver-side dedup keys line up.
     */
    private boolean replayEvent(Session session, ExecutionEvent event) {
        UUID executionId = executionIdFor(session, event);
        if (executionId == null) {
            return false;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("executionId", executionId);
        payload.put("eventId", event.getSourceEventId());
        payload.put("eventType", event.getMessage());
        payload.put("occurredAt", event.getCreatedAt());
        payload.put("data", event.getData() == null ? Map.of() : event.getData());

        HostProtocolEnvelope envelope = HostProtocolEnvelope.reply(
                HostProtocol.EXECUTION_EVENT, null, payload, objectMapper);
        envelope.setExecutionId(executionId);
        envelope.setSessionId(session.getId());
        envelope.setSequence(event.getSequenceNumber().intValue());
        try {
            return frameRelayService.send(session.getId(), session.getHostInstanceId(),
                    objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            log.warn("Replay send failed for event {} (session {}): {}",
                    event.getId(), session.getId(), e.getMessage());
            return false;
        }
    }

    /** The execution the event's §8.4 frame rode — resolvable by dispatch (workflow) or session history (conversation). */
    private UUID executionIdFor(Session session, ExecutionEvent event) {
        if (event.getAttemptId() != null) {
            // Orchestration events correlate dispatch == attempt == dispatchId.
            return executionRepository.findBySessionId(session.getId()).stream()
                    .filter(e -> event.getAttemptId().equals(e.getDispatchId()))
                    .map(SessionExecution::getId)
                    .findFirst()
                    .orElse(null);
        }
        // Conversation events: the session's newest execution is the turn
        // the event belonged to (request_id == conversation id).
        return executionRepository.findBySessionIdOrderBySequenceNoDesc(session.getId()).stream()
                .filter(e -> e.getState() != SessionExecution.State.REJECTED)
                .map(SessionExecution::getId)
                .findFirst()
                .orElse(null);
    }

    /**
     * §8.4 persistence-side filter: the event belongs to this session when
     * it correlates with one of the session's executions. Orchestration
     * rows join via the attempt tuple ({@code attempt_id} == dispatchId);
     * conversation rows are session-scoped by construction (Wave 1b
     * ingestion keys them off the session's execution), so their presence
     * is sufficient — the per-event replay send carries the session id, and
     * a stale cross-session match cannot occur because conversation rows
     * are only ever ingested from one session's executions.
     */
    private boolean sameSession(Session session, ExecutionEvent event) {
        List<SessionExecution> executions = executionRepository.findBySessionId(session.getId());
        for (SessionExecution execution : executions) {
            if (event.getAttemptId() != null
                    && event.getAttemptId().equals(execution.getDispatchId())) {
                return true;
            }
        }
        return "CONVERSATION".equals(session.getServiceType())
                && !executions.isEmpty();
    }
}