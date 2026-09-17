// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host;

import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionAllocator;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.inference.execution.DurableEventReplayService;
import ai.myrmec.engine.inference.execution.SessionExecution;
import ai.myrmec.engine.inference.execution.SessionExecutionRepository;
import ai.myrmec.engine.node.NodeRegistryService;
import ai.myrmec.engine.websocket.host.payload.HostResumePayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * §13 reconnect and reconciliation (A2): the engine is authoritative because
 * it owns durable session/execution state. Given a reconnected host's
 * {@code host.resume} report, this service
 *
 * <ol>
 *   <li>matches the reported (previousInstanceId, instanceNonce) against the
 *       RECOVERING instance (§6.1 nonce identity — a nonce mismatch is an
 *       identity conflict, never a silent re-adoption),</li>
 *   <li>decides per reported session — {@code KEEP} (only when
 *       {@code capacityHeld} is true, §13 slot-ownership rule; carries the
 *       engine's {@code highestContiguousSequence} as the
 *       {@code resumeFromSequence} replay cursor), {@code CANCEL_EXECUTION}
 *       for in-flight orchestration executions with no recorded outcome (the
 *       host applies execution.cancel semantics, §13), and {@code CLOSE}
 *       otherwise (durably-recorded orchestration results, paused
 *       executions, sessions the engine already closed in the HOST_LOST
 *       race, capacity not held, unknown sessions),</li>
 *   <li>re-adopts the instance when at least one session was kept —
 *       RECOVERING → OPEN, socket re-homed to the node now serving it
 *       (A3 routing).</li>
 * </ol>
 *
 * <p>In-flight executions during the window are PARKED, not closed (§13):
 * a KEEP decision leaves non-terminal execution rows in their recorded
 * state; the host reports their status and the engine's dedup substrate
 * ({@code terminal_message_id}) rejects conflicting replays. A paused
 * orchestration execution is the §13-mandated exception — its session
 * closes; continuation is not an active session.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class HostResumeReconcileService {

    private final AgentHostInstanceRepository instanceRepository;
    private final SessionRepository sessionRepository;
    private final SessionExecutionRepository executionRepository;
    private final SessionAllocator sessionAllocator;
    private final NodeRegistryService nodeRegistryService;
    private final DurableEventReplayService eventReplayService;
    private final ai.myrmec.engine.node.HostFrameRelayService frameRelayService;
    private final ObjectMapper objectMapper;

    /** §13 match outcome — why a resume cannot re-adopt. */
    public sealed interface MatchResult {
        record Matched(AgentHostInstance instance) implements MatchResult {}
        record NoInstance() implements MatchResult {}
        record NonceMismatch(UUID instanceId) implements MatchResult {}
        record Expired(UUID instanceId) implements MatchResult {}
    }

    /**
     * Resolve the RECOVERING instance the resume refers to. The instance
     * must be in RECOVERING and inside its retention window; a matched
     * nonce on an already-closed row means the window expired in a race —
     * reported as {@link Expired}, and the host falls back to host.open.
     */
    @Transactional(readOnly = true)
    public MatchResult match(UUID hostId, UUID previousInstanceId, UUID instanceNonce) {
        if (previousInstanceId == null) {
            return new MatchResult.NoInstance();
        }
        AgentHostInstance instance = instanceRepository.findById(previousInstanceId)
                .orElse(null);
        if (instance == null || instance.getStatus() == AgentHostInstance.Status.CLOSED) {
            return instance == null
                    ? new MatchResult.NoInstance()
                    : new MatchResult.Expired(instance.getId());
        }
        if (instance.getStatus() != AgentHostInstance.Status.RECOVERING) {
            // Still OPEN = a live duplicate connection; resume is meaningless.
            return new MatchResult.NonceMismatch(instance.getId());
        }
        if (instance.getRecoveryExpiresAt() != null
                && instance.getRecoveryExpiresAt().isBefore(Instant.now())) {
            return new MatchResult.Expired(instance.getId());
        }
        if (instanceNonce == null
                || !instance.getInstanceNonce().equals(instanceNonce.toString())
                || !instance.getAgentHostId().equals(hostId)) {
            return new MatchResult.NonceMismatch(instance.getId());
        }
        return new MatchResult.Matched(instance);
    }

    /**
     * §13 decide + re-adopt. The returned decisions list drives the
     * {@code host.reconcile} frame; when at least one session is kept the
     * instance transitions back to OPEN on {@code node}.
     */
    @Transactional
    public List<HostResumePayload.ReconcilePayload.Decision> reconcile(
            AgentHostInstance instance, List<HostResumePayload.RetainedSession> reported) {
        List<HostResumePayload.ReconcilePayload.Decision> decisions = new ArrayList<>();
        Set<UUID> kept = new HashSet<>();
        boolean capacityViolationWarned = false;

        for (HostResumePayload.RetainedSession retained : reported) {
            Session session = retained.sessionId() == null
                    ? null : sessionRepository.findById(retained.sessionId()).orElse(null);

            // §13: a session unknown to the engine (already closed in the
            // HOST_LOST race, or never opened) → CLOSE.
            if (session == null
                    || !instance.getId().equals(session.getHostInstanceId())
                    || SessionAllocator.ALLOC_STATE_CLOSED.equals(session.getAllocationState())) {
                decisions.add(HostResumePayload.ReconcilePayload.Decision.close(
                        retained.sessionId(), HostProtocol.SESSION_NOT_FOUND));
                continue;
            }

            // §13: KEEP requires the host to hold a local slot. Without it
            // the capacity assertion fails → CLOSE (a later turn allocates
            // normally). Host-reported capacity never creates an allocation.
            if (!retained.capacityHeld()) {
                if (!capacityViolationWarned) {
                    log.warn("Resume for instance {} reports capacityHeld=false for "
                            + "session {} — closing (§13 slot-ownership rule)",
                            instance.getId(), retained.sessionId());
                    capacityViolationWarned = true;
                }
                closeParked(session, "RECONCILE_NO_CAPACITY");
                decisions.add(HostResumePayload.ReconcilePayload.Decision.close(
                        retained.sessionId(), HostProtocol.NO_CAPACITY));
                continue;
            }

            // In-flight orchestration execution with unknown outcome: the
            // engine cannot know what the host finished while disconnected —
            // CANCEL_EXECUTION is itself the cancellation command (§13): the
            // engine marks CANCELLING (the §11.2 pre-terminal mark) and the
            // host applies execution.cancel semantics, emitting the normal
            // execution.cancelled terminal frame; the engine does NOT send a
            // second execution.cancel.
            if ("WORKFLOW".equals(session.getServiceType())) {
                UUID inFlight = inFlightOrchestrationExecution(session.getId());
                if (inFlight != null) {
                    executionRepository.findById(inFlight).ifPresent(e -> {
                        e.setState(SessionExecution.State.CANCELLING);
                        executionRepository.save(e);
                    });
                    decisions.add(HostResumePayload.ReconcilePayload.Decision.cancelExecution(
                            retained.sessionId(), inFlight));
                    continue;
                }
                // Durably recorded outcome (or none in flight) → the §13
                // one-shot session closes; the attempt is already terminal.
                closeSession(session);
                decisions.add(HostResumePayload.ReconcilePayload.Decision.close(
                        retained.sessionId(), HostProtocol.TASK_ALREADY_RETRIED));
                continue;
            }

            // Conversation session: KEEP and re-open its dedicated channel.
            // Parked non-terminal executions keep their recorded state; the
            // host's replay of durable events + terminal resends reconciles
            // them (§12.1 dedup by terminal_message_id).
            long cursor = session.getHighestContiguousSequence();
            decisions.add(HostResumePayload.ReconcilePayload.Decision.keep(
                    session.getId(), cursor));
            kept.add(session.getId());
            // §12.3/§13: drain the engine's retained durable events after
            // the host's reported cursor onto the resumed socket. The host
            // replays its own retained frames after resumeFromSequence too
            // (§13 ordering), so both directions converge; receiver-side
            // ingestion dedups by source_event_id.
            long hostCursor = retained.lastSentSequence() != null
                    ? retained.lastSentSequence() : cursor;
            eventReplayService.replay(session.getId(), hostCursor);
            // §13: resend terminal messages the host has not acknowledged —
            // the engine's recorded terminal_message_id vs the host's
            // lastAcknowledgedMessageId decides (§12.1 dedup: a host that
            // already has the terminal frame drops the resend).
            resendUnacknowledgedTerminals(session, retained);
        }

        // §6.1 append-only: the supervisor IS back and owns the nonce —
        // re-adopt the row regardless of how many sessions survived. Its
        // socket's node is authoritative from here on (A3 routing).
        sessionAllocator.resumeInstance(instance, nodeRegistryService.getSelfNodeId());
        if (!kept.isEmpty()) {
            log.info("Reconciled instance {}: {} session(s) kept, {} decisions total",
                    instance.getId(), kept.size(), decisions.size());
        }
        return decisions;
    }

    /** The one non-terminal orchestration execution on the session, if any. */
    private UUID inFlightOrchestrationExecution(UUID sessionId) {
        return executionRepository.findBySessionId(sessionId).stream()
                .filter(e -> e.getState() == SessionExecution.State.STARTING
                        || e.getState() == SessionExecution.State.RUNNING
                        || e.getState() == SessionExecution.State.CANCELLING)
                .map(SessionExecution::getId)
                .findFirst()
                .orElse(null);
    }

    /** Terminal-close a reconciled-away session (slot returns; §9 semantics). */
    private void closeParked(Session session, String reasonCode) {
        sessionAllocator.close(session.getId(), reasonCode);
    }

    /** Terminal-close a one-shot workflow session whose outcome is recorded. */
    private void closeSession(Session session) {
        sessionAllocator.close(session.getId(), HostProtocol.TASK_ALREADY_RETRIED);
    }

    /**
     * §13: resend the engine's recorded terminal frames for executions the
     * host has not acknowledged (its {@code lastAcknowledgedMessageId}
     * differs from the engine's {@code terminal_message_id}). Frames ride
     * the session's socket (channel-first, node-aware); the host's §12.1
     * dedup drops any it already holds, so a resend is always safe.
     */
    private void resendUnacknowledgedTerminals(Session session,
                                               HostResumePayload.RetainedSession retained) {
        List<SessionExecution> terminal = executionRepository.findBySessionId(session.getId())
                .stream()
                .filter(e -> e.getTerminalMessageId() != null)
                .toList();
        if (terminal.isEmpty()) {
            return;
        }
        boolean acked = retained.lastAcknowledgedMessageId() != null;
        for (SessionExecution execution : terminal) {
            if (acked && execution.getTerminalMessageId()
                    .equals(retained.lastAcknowledgedMessageId())) {
                continue; // host already holds this terminal frame
            }
            eventTerminalResend(session, execution);
        }
    }

    /** Re-send one recorded terminal outcome as its §8.5/8.6/8.8 frame. */
    private void eventTerminalResend(Session session, SessionExecution execution) {
        String type = switch (execution.getState()) {
            case COMPLETED -> HostProtocol.EXECUTION_COMPLETE;
            case FAILED -> HostProtocol.EXECUTION_FAILED;
            case PAUSED -> HostProtocol.EXECUTION_PAUSED;
            case CANCELLED -> HostProtocol.EXECUTION_CANCELLED;
            default -> null;
        };
        if (type == null) {
            return;
        }
        try {
            // Re-send the recorded payload verbatim; the messageId is the
            // dedup key — a resent terminal frame is idempotent (§12.1).
            Map<String, Object> payload = new LinkedHashMap<>(
                    execution.getTerminalPayload() == null
                            ? Map.of() : execution.getTerminalPayload());
            payload.putIfAbsent("executionId", execution.getId());
            HostProtocolEnvelope envelope = HostProtocolEnvelope.reply(type,
                    null, payload, objectMapper);
            envelope.setExecutionId(execution.getId());
            envelope.setSessionId(session.getId());
            envelope.setMessageId(execution.getTerminalMessageId());
            frameRelayService.send(session.getId(), session.getHostInstanceId(),
                    objectMapper.writeValueAsString(envelope));
            log.debug("Resent terminal {} for execution {} (session {})",
                    type, execution.getId(), session.getId());
        } catch (Exception e) {
            log.warn("Terminal resend failed for execution {}: {}",
                    execution.getId(), e.getMessage());
        }
    }
}