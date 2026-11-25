// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution;

import ai.myrmec.engine._protocol.HostProtocol;
import ai.myrmec.engine._protocol.HostProtocolEnvelope;
import ai.myrmec.engine._protocol.payload.ExecutionCancelPayload;
import ai.myrmec.engine._protocol.payload.ExecutionPolicyUpdatePayload;
import ai.myrmec.engine._protocol.payload.ExecutionStartPayload;
import ai.myrmec.engine._protocol.payload.OrchestrationExecutionStartPayload;
import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.node.HostFrameRelayService;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Engine→host execution commands (§8.1/§8.8) over the host socket — local or
 * relayed (§20, decision H5): the frame is serialized once and handed to
 * {@link HostFrameRelayService}, which delivers it to the owning replica's
 * socket directly when the instance is homed here, or relays it over the HTTP
 * mesh when the instance lives on a peer (falling back to a local socket if
 * one exists, per the relay's failure semantics).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExecutionCommandSender {

    private final HostFrameRelayService relayService;
    private final SessionRepository sessionRepository;
    private final ObjectMapper objectMapper;

    @Value("${myrmec.host.execution-start-timeout-seconds:300}")
    private int executionStartTimeoutSeconds;

    /** sessionId → the serialized execution.start frame awaiting channel bind. */
    private final Map<UUID, String> pendingStarts = new ConcurrentHashMap<>();

    /** §8.1: send execution.start for a conversation turn. */
    public boolean startConversation(SessionExecution execution, Session session,
                                     ExecutionStartPayload payload) {
        return sendExecutionStart(execution, session, payload);
    }

    /**
     * §8.1: send execution.start for an ordinary (non-orchestration) workflow
     * step — the same §8.1 input-block shape as a conversation turn, shipped on
     * the WORKFLOW session the allocator gave the task.
     */
    public boolean startExecution(SessionExecution execution, Session session,
                                  ExecutionStartPayload payload) {
        return sendExecutionStart(execution, session, payload);
    }

    /** §8.1: send execution.start for an orchestration attempt (§16.2 shape). */
    public boolean startOrchestration(SessionExecution execution, Session session,
                                      OrchestrationExecutionStartPayload payload) {
        return sendExecutionStart(execution, session, payload);
    }

    /**
     * Shared execution.start arm: serialize, then deliver on the dedicated
     * channel if bound; otherwise queue the frame and deliver it on channel
     * bind (the agent SDK never receives execution.* on the control socket).
     */
    private boolean sendExecutionStart(SessionExecution execution, Session session, Object payload) {
        HostProtocolEnvelope envelope = HostProtocolEnvelope.reply(HostProtocol.EXECUTION_START, null, payload, objectMapper);
        envelope.setExecutionId(execution.getId());
        envelope.setSessionId(session.getId());
        envelope.setTurn(execution.getSequenceNo());
        try {
            String json = objectMapper.writeValueAsString(envelope);
            boolean sent = relayService.sendDedicated(session.getId(), session.getHostInstanceId(), json);
            if (!sent) {
                // Channel not bound yet — queue for the bind flush.
                pendingStarts.put(session.getId(), json);
                log.debug("Queued execution.start for session {} (channel not bound yet)", session.getId());
            }
            return sent;
        } catch (Exception e) {
            return false;
        }
    }

    /** Flush a queued execution.start once the session's channel binds. */
    public void flushPendingStart(UUID sessionId) {
        String json = pendingStarts.remove(sessionId);
        if (json == null) {
            return;
        }
        ai.myrmec.engine.inference.Session session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return;
        }
        boolean sent = relayService.sendDedicated(sessionId, session.getHostInstanceId(), json);
        if (!sent) {
            // Still unbound (shouldn't happen on a bind flush) — re-queue.
            pendingStarts.put(sessionId, json);
        } else {
            log.debug("Flushed queued execution.start for session {}", sessionId);
        }
    }

    /** Drop any queued start for a session (reject / close). */
    public void discardPendingStart(UUID sessionId) {
        pendingStarts.remove(sessionId);
    }

    /** §8.8: request cancellation. */
    public boolean cancel(SessionExecution execution, Session session, UUID dispatchId,
                          String reasonCode, int gracePeriodSeconds) {
        return send(session.getHostInstanceId(), HostProtocol.EXECUTION_CANCEL,
                null, new ExecutionCancelPayload(execution.getId(), dispatchId, reasonCode,
                        java.time.Instant.now(), gracePeriodSeconds),
                execution.getId(), session.getId(), execution.getSequenceNo());
    }

    /**
     * §8.7: send an tighten-only policy update carrying the engine's durably
     * accounted usage. Called by {@link SessionPolicyService} when a session's
     * accounted usage advances past the last-sent snapshot.
     */
    public boolean policyUpdate(SessionExecution execution, Session session, UUID dispatchId,
                                ExecutionPolicyUpdatePayload payload) {
        return send(session.getHostInstanceId(), HostProtocol.EXECUTION_POLICY_UPDATE,
                null, payload, execution.getId(), session.getId(), execution.getSequenceNo());
    }

    /**
     * Serialize once, then route on the session's dedicated channel (the agent
     * SDK receives all execution.* frames there). Sets the envelope's {@code turn}
     * (the per-session {@code sequenceNo}) — the agent-channel identity model.
     */
    private boolean send(UUID hostInstanceId, String type, String correlationId,
                         Object payload, UUID executionId, UUID sessionId, Integer turn) {
        HostProtocolEnvelope envelope = HostProtocolEnvelope.reply(type, correlationId, payload, objectMapper);
        envelope.setExecutionId(executionId);
        envelope.setSessionId(sessionId);
        envelope.setTurn(turn);
        try {
            String json = objectMapper.writeValueAsString(envelope);
            return relayService.sendDedicated(sessionId, hostInstanceId, json);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * §3.4 (plan 2026-10-03-session-interaction): send an ALREADY-PERSISTED
     * envelope. The outbox row's map IS the wire envelope (its {@code id} is
     * the frame's {@code messageId}); this re-serializes the STORED map and
     * hands the exact bytes to the relay — never a rebuilt envelope, never a
     * reminted messageId. Dedicated-channel first (§22.3/D7: session frames
     * ride the session's bound Agent Channel). Returns false when nothing
     * was delivered; the outbox row stays PENDING for retransmission.
     */
    public boolean sendPersisted(UUID sessionId, UUID hostInstanceId,
                                 Map<String, Object> storedEnvelope) {
        try {
            String json = objectMapper.writeValueAsString(storedEnvelope);
            boolean sent = relayService.sendDedicated(sessionId, hostInstanceId, json);
            if (!sent) {
                log.debug("Persisted envelope {} undelivered on the dedicated channel "
                        + "(session {}) — outbox row stays PENDING", storedEnvelope.get("messageId"), sessionId);
            }
            return sent;
        } catch (Exception e) {
            log.warn("Persisted-envelope send failed (session {}): {}", sessionId, e.getMessage());
            return false;
        }
    }
}