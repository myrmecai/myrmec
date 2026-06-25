package ai.myrmec.engine.conversation;

import ai.myrmec.engine.agent.Agent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * separate transaction ({@code REQUIRES_NEW}) so that a constraint violation
 * (e.g. a seq collision from concurrent re-attach) never rolls back the
 * caller's state change.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationEventService {

    private final ConversationEventRepository repository;
    private final ConversationRepository conversationRepository;

    /**
     * Append one lifecycle event. No-op (returns {@code null}) when
     * {@code conversationId} is null or references a conversation that does
     * not exist — an unattributed transition is not logged rather than
     * failing the caller (the {@code conversation_id} foreign key is enforced,
     * so a stale id would otherwise abort the transition).
     *
     * @param conversationId the conversation this transition belongs to
     * @param agentId        the ephemeral worker serving the attempt (nullable)
     * @param agentHostId    the durable machine it ran on (nullable)
     * @param fromState      runtime status before the transition (nullable)
     * @param toState        runtime status after the transition
     * @param reasonCode     why the transition happened
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public ConversationEvent record(UUID conversationId, UUID agentId, UUID agentHostId,
                                    Agent.Status fromState, Agent.Status toState,
                                    ConversationEventReason reasonCode) {
        if (conversationId == null || !conversationRepository.existsById(conversationId)) {
            return null;
        }

        try {
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
            log.debug("conversation {} event #{} {}->{} ({}) attempt {}",
                    conversationId, event.getSeq(), event.getFromState(), event.getToState(),
                    reasonCode, bindAttemptNo);
            return event;
        } catch (Exception e) {
            // Best-effort: a seq collision from concurrent re-attach or any
            // other DB error must never roll back the caller's lifecycle
            // transition. Log and return null so the caller proceeds.
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
