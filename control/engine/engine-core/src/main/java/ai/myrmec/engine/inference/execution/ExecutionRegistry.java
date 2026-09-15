// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution;

import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionAllocator;
import ai.myrmec.engine.inference.SessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Engine-owned execution state machine (protocol §11.2). STARTING→RUNNING at
 * accept; exactly one terminal transition (§11.3.6) recorded with its
 * messageId for §12.1 dedup. One in-flight execution per conversation
 * session (§11.3.4); exactly one execution per orchestration session
 * (§11.3.5) — both enforced under the session-row lock.
 */
@Service
@Slf4j
public class ExecutionRegistry {

    /** States that block a new execution on the session. */
    private static final List<SessionExecution.State> IN_FLIGHT =
            List.of(SessionExecution.State.STARTING, SessionExecution.State.RUNNING,
                    SessionExecution.State.CANCELLING);

    private final SessionExecutionRepository executionRepository;
    private final SessionRepository sessionRepository;

    public ExecutionRegistry(SessionExecutionRepository executionRepository,
                             SessionRepository sessionRepository) {
        this.executionRepository = executionRepository;
        this.sessionRepository = sessionRepository;
    }

    /**
     * §8.1: create the STARTING execution row. Conversation sessions enforce
     * §11.3.4 (one in flight) and assign the next sequenceNo; orchestration
     * sessions enforce §11.3.5 (exactly one, ever). The session must be
     * allocation-ACTIVE (§7.4: no execution.start before session.opened).
     */
    @Transactional
    public Optional<SessionExecution> start(UUID sessionId, String requestId, Instant deadline,
                                            Map<String, Object> inputPayload) {
        return start(sessionId, requestId, null, deadline, inputPayload);
    }

    /**
     * &sect;8.1 with an engine-authored dispatch identity: an orchestration
     * attempt stamps its {@code dispatchId} (= the attempt UUID in V1) at start,
     * so the execution row is correlated with the durable dispatch before the
     * host has answered {@code execution.accept}. The host's accept carries the
     * same dispatchId; a mismatch is recorded, never silently overwritten.
     */
    @Transactional
    public Optional<SessionExecution> start(UUID sessionId, String requestId, UUID dispatchId,
                                            Instant deadline,
                                            Map<String, Object> inputPayload) {
        Session session = sessionRepository.findWithLockById(sessionId).orElse(null);
        if (session == null) {
            return Optional.empty();
        }
        if (!SessionAllocator.ALLOC_STATE_ACTIVE.equals(session.getAllocationState())) {
            log.warn("execution.start refused — session {} not allocation-ACTIVE ({})",
                    sessionId, session.getAllocationState());
            return Optional.empty();
        }
        boolean conversation = "CONVERSATION".equals(session.getServiceType());
        List<SessionExecution> inFlight =
                executionRepository.findWithLockBySessionIdAndStateIn(sessionId, IN_FLIGHT);
        if (!inFlight.isEmpty()) {
            log.warn("execution.start refused — session {} already has in-flight execution {}",
                    sessionId, inFlight.get(0).getId());
            return Optional.empty();
        }
        SessionExecution execution = new SessionExecution();
        execution.setSessionId(sessionId);
        execution.setServiceType(session.getServiceType());
        execution.setRequestId(requestId);
        execution.setDeadline(deadline);
        execution.setInputPayload(inputPayload);
        execution.setDispatchId(dispatchId);
        execution.setState(SessionExecution.State.STARTING);
        if (conversation) {
            int maxSeq = executionRepository.findBySessionIdOrderBySequenceNoDesc(sessionId)
                    .stream().findFirst().map(e -> e.getSequenceNo() == null ? 0
                            : e.getSequenceNo()).orElse(0);
            execution.setSequenceNo(maxSeq + 1);
        }
        // §11.3.5: orchestration sessions accept exactly one execution EVER
        // (not just one in-flight) — a second start on the same orchestration
        // session is a protocol violation.
        if (!conversation && !executionRepository.findBySessionId(sessionId).isEmpty()) {
            log.warn("execution.start refused — orchestration session {} already has an execution",
                    sessionId);
            return Optional.empty();
        }
        return Optional.of(executionRepository.saveAndFlush(execution));
    }

    /** §8.2 accept: STARTING→RUNNING, record accept metadata, stamp startedAt. */
    @Transactional
    public boolean accept(UUID executionId, Instant startedAt, String resolvedModelId,
                           UUID dispatchId, String assignmentDigest) {
        SessionExecution execution = executionRepository.findById(executionId).orElse(null);
        if (execution == null || execution.getState() != SessionExecution.State.STARTING) {
            return false;
        }
        execution.setState(SessionExecution.State.RUNNING);
        execution.setStartedAt(startedAt != null ? startedAt : Instant.now());
        execution.setResolvedModelId(resolvedModelId);
        // The dispatch identity is engine-authored at start, and only for an
        // orchestration attempt (§16.2): it is the discriminator the terminal
        // bridge uses to route an outcome to the orchestration sink. The host
        // echoes the same id on accept; a mismatch is audited, never applied.
        if (execution.getDispatchId() != null && dispatchId != null
                && !dispatchId.equals(execution.getDispatchId())) {
            log.warn("execution.accept for {} reports dispatchId {} but the execution was started "
                    + "with {} — keeping the engine-authored identity",
                    executionId, dispatchId, execution.getDispatchId());
        }
        execution.setAssignmentDigest(assignmentDigest);
        executionRepository.save(execution);
        return true;
    }

    /** §8.2 reject: STARTING→REJECTED. Idempotent replay returns the recorded outcome. */
    @Transactional
    public boolean reject(UUID executionId) {
        SessionExecution execution = executionRepository.findById(executionId).orElse(null);
        if (execution == null || execution.getState() != SessionExecution.State.STARTING) {
            return false;
        }
        execution.setState(SessionExecution.State.REJECTED);
        execution.setTerminalAt(Instant.now());
        executionRepository.save(execution);
        return true;
    }

    /**
     * §8.5/§8.6/§8.8 terminal transition: RUNNING→{COMPLETED,FAILED,PAUSED,CANCELLED}
     * (or CANCELLING→{CANCELLED,COMPLETED,FAILED} — §11.2 completion/failure won
     * races). Records the terminal messageId for §12.1 dedup: a replay with the
     * SAME messageId returns the stored outcome (true, idempotent); the state is
     * never double-transitioned. A DIFFERENT messageId for the same execution
     * fails closed (returns false) — conflicting terminal frames are a protocol
     * violation, never silently overwritten.
     */
    @Transactional
    public boolean terminal(UUID executionId, SessionExecution.State terminalState,
                            String messageId, Map<String, Object> terminalPayload) {
        SessionExecution execution = executionRepository.findWithLockById(executionId).orElse(null);
        if (execution == null) {
            return false;
        }
        if (execution.getTerminalMessageId() != null) {
            return execution.getTerminalMessageId().equals(messageId);
        }
        boolean runningTerminal = execution.getState() == SessionExecution.State.RUNNING
                && (terminalState == SessionExecution.State.COMPLETED
                    || terminalState == SessionExecution.State.FAILED
                    || terminalState == SessionExecution.State.PAUSED);
        boolean cancellingTerminal = execution.getState() == SessionExecution.State.CANCELLING
                && (terminalState == SessionExecution.State.CANCELLED
                    || terminalState == SessionExecution.State.COMPLETED
                    || terminalState == SessionExecution.State.FAILED);
        if (!runningTerminal && !cancellingTerminal) {
            return false;
        }
        execution.setState(terminalState);
        execution.setTerminalMessageId(messageId);
        execution.setTerminalPayload(terminalPayload);
        execution.setTerminalAt(Instant.now());
        executionRepository.save(execution);
        return true;
    }

    /**
     * §8.8: mark the execution CANCELLING. Unlike terminal(), this is a state mark,
     * NOT a terminal transition — it does not consume the terminal slot; the real
     * terminal frame that follows (execution.cancelled/complete/failed) does.
     * Idempotent: an already-CANCELLING or terminal execution is a no-op true.
     */
    @Transactional
    public boolean requestCancel(UUID executionId) {
        SessionExecution execution = executionRepository.findWithLockById(executionId).orElse(null);
        if (execution == null) {
            return false;
        }
        if (execution.getState() == SessionExecution.State.RUNNING) {
            execution.setState(SessionExecution.State.CANCELLING);
            executionRepository.save(execution);
        }
        return true;
    }

    /** §12.3: advance the session's durable-event sequence cursor (idempotent: max()). */
    @Transactional
    public long acknowledgeEventSequence(UUID sessionId, long sequence) {
        Session session = sessionRepository.findWithLockById(sessionId).orElse(null);
        if (session == null) {
            return 0;
        }
        if (sequence > session.getHighestContiguousSequence()) {
            session.setHighestContiguousSequence(sequence);
            sessionRepository.save(session);
        }
        return session.getHighestContiguousSequence();
    }
}
