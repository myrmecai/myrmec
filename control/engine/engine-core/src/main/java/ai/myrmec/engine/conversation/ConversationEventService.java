package ai.myrmec.engine.conversation;

import ai.myrmec.engine.agent.Agent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Appends transitions to the immutable {@link ConversationEvent} log
 * (conversation-observability §4). One call per FSM transition; the service
 * owns the bookkeeping every caller would otherwise duplicate:
 *
 * <ul>
 *   <li><b>{@code seq}</b> — the next monotonic sequence for the conversation
 *       (first event is {@code 0}).</li>
 *   <li><b>{@code bindAttemptNo}</b> — derived from how many {@code RESERVED}
 *       events the conversation has had: a new reserve opens the next attempt;
 *       every later event in that attempt (ack / bound / release / timeout)
 *       carries the current attempt number.</li>
 * </ul>
 *
 * <p>Recording is best-effort attribution and must never break the lifecycle
 * transition that triggered it: a worker that is not (yet) tied to a
 * conversation — or one tied to a conversation that no longer exists — has
 * nothing to attribute, so the call is a no-op. The event is recorded in a
 * separate programmatic transaction so that a constraint violation (e.g. a
 * seq collision from concurrent re-attach) never rolls back the caller's
 * state change.</p>
 *
 * <p><b>Implementation note.</b> The previous design used
 * {@code @Transactional(REQUIRES_NEW)} with a try-catch inside the method
 * body. That was unsound: when {@code repository.save()} deferred the INSERT
 * to commit time, the {@code DataIntegrityViolationException} was thrown
 * <em>after</em> the try-catch returned — by the transaction interceptor at
 * commit — which propagated an {@code UnexpectedRollbackException} back to
 * the caller and rolled back the caller's own transaction (undoing the
 * agent-status save). Adding {@code repository.flush()} inside the
 * try-catch fixed the timing but not the outcome: Hibernate marks the
 * persistence context rollback-only when {@code flush()} throws, so Spring
 * still raised {@code UnexpectedRollbackException} at commit. The current
 * design uses a {@link TransactionTemplate} with {@code REQUIRES_NEW}
 * propagation so that a failed inner transaction is caught and swallowed
 * <em>entirely</em> — the caller never sees an exception and its own
 * transaction is unaffected.</p>
 */
@Service
@Slf4j
public class ConversationEventService {

    private final ConversationEventRepository repository;
    private final ConversationRepository conversationRepository;
    private final TransactionTemplate recordTxTemplate;

    public ConversationEventService(ConversationEventRepository repository,
                                    ConversationRepository conversationRepository,
                                    PlatformTransactionManager txManager) {
        this.repository = repository;
        this.conversationRepository = conversationRepository;
        this.recordTxTemplate = new TransactionTemplate(txManager);
        this.recordTxTemplate.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Append one lifecycle event. No-op (returns {@code null}) when
     * {@code conversationId} is null or references a conversation that does
     * not exist — an unattributed transition is not logged rather than
     * failing the caller (the {@code conversation_id} foreign key is enforced,
     * so a stale id would otherwise abort the transition).
     *
     * <p>Uses a programmatic {@code REQUIRES_NEW} transaction via
     * {@link TransactionTemplate} so that any DB-level failure (seq collision,
     * etc.) is fully caught and swallowed — the caller's transaction is
     * never rolled back or marked rollback-only because of a best-effort
     * event-recording failure.</p>
     *
     * @param conversationId the conversation this transition belongs to
     * @param agentId        the ephemeral worker serving the attempt (nullable)
     * @param agentHostId    the durable machine it ran on (nullable)
     * @param fromState      runtime status before the transition (nullable)
     * @param toState        runtime status after the transition
     * @param reasonCode     why the transition happened
     */
    public ConversationEvent record(UUID conversationId, UUID agentId, UUID agentHostId,
                                    Agent.Status fromState, Agent.Status toState,
                                    ConversationEventReason reasonCode) {
        if (conversationId == null || !conversationRepository.existsById(conversationId)) {
            return null;
        }

        try {
            return recordTxTemplate.execute(status -> {
                long reserves = repository.countByConversationIdAndReasonCode(
                        conversationId, ConversationEventReason.RESERVED);
                int bindAttemptNo = reasonCode == ConversationEventReason.RESERVED
                        ? (int) reserves + 1
                        : (int) Math.max(reserves, 1);

                ConversationEvent event = new ConversationEvent();
                event.setConversationId(conversationId);
                event.setSeq(repository.findMaxSeq(conversationId) + 1);
                event.setFromState(fromState != null ? fromState.name() : null);
                event.setToState(toState.name());
                event.setReasonCode(reasonCode);
                event.setAgentId(agentId);
                event.setAgentHostId(agentHostId);
                event.setBindAttemptNo(bindAttemptNo);

                event = repository.save(event);
                // Flush inside the transaction so the INSERT (and any
                // constraint violation) happens HERE, not at commit time.
                repository.flush();
                log.debug("conversation {} event #{} {}->{} ({}) attempt {}",
                        conversationId, event.getSeq(), event.getFromState(), event.getToState(),
                        reasonCode, bindAttemptNo);
                return event;
            });
        } catch (Exception e) {
            // Best-effort: a seq collision from concurrent re-attach or any
            // other DB error must never roll back the caller's lifecycle
            // transition. The TransactionTemplate catches the inner exception
            // and rolls back the inner transaction; we swallow it here so
            // the caller proceeds unaffected.
            log.warn("Best-effort event recording failed for conversation {} ({}->{} {}): {}",
                    conversationId, fromState, toState, reasonCode, e.getMessage());
            return null;
        }
    }

    /**
     * Replay a conversation's lifecycle log in sequence order (oldest first).
     * Returns an empty list when the conversation has no events yet.
     */
    @Transactional(readOnly = true)
    public List<ConversationEvent> findByConversation(UUID conversationId) {
        return repository.findByConversationIdOrderBySeqAsc(conversationId);
    }
}
