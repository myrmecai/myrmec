package ai.myrmec.engine.conversation.dispatch;

import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentRepository;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationMessage;
import ai.myrmec.engine.conversation.ConversationNoticeService;
import ai.myrmec.engine.conversation.ConversationRepository;
import ai.myrmec.engine.conversation.ConversationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Re-dispatches conversations whose USER turn was buffered because no worker was
 * online at post time (#86 / #87). Wakes on {@link AgentAvailableEvent} — fired
 * when an agent instance's control socket connects and the instance is released
 * to IDLE — and walks the ACTIVE conversations pinned to that worker's host,
 * re-dispatching the first one with a still-pending turn.
 *
 * <p>Best-effort and node-local: a single newly-available worker can serve one
 * turn, so the drainer stops after the first successful dispatch. A later
 * connect (or the next worker freeing up) drains the rest. Per-conversation
 * errors are swallowed so one bad thread never blocks the others.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationBacklogDrainer {

    /** Worker statuses that mean a conversation's turn is already in flight. */
    private static final List<Agent.Status> ACTIVE_BINDING_STATUSES = List.of(
            Agent.Status.RESERVED,
            Agent.Status.CONNECTING,
            Agent.Status.BOUND,
            Agent.Status.DRAINING);

    private final AgentRepository agentInstanceRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationService conversationService;
    private final ConversationNoticeService conversationNoticeService;
    private final ConversationTurnDispatcher turnDispatcher;

    @EventListener
    public void onAgentAvailable(AgentAvailableEvent event) {
        UUID hostId = agentInstanceRepository.findById(event.agentInstanceId())
                .map(Agent::getAgentHostId)
                .orElse(null);
        if (hostId == null) {
            return;
        }

        List<Conversation> candidates =
                conversationRepository.findByAgentIdAndStatus(hostId, Conversation.Status.ACTIVE);
        if (candidates.isEmpty()) {
            return;
        }

        int drained = 0;
        for (Conversation conv : candidates) {
            if (!hasPendingTurn(conv.getId())) {
                continue;
            }
            try {
                boolean dispatched = turnDispatcher.dispatch(conv.getId());
                if (dispatched) {
                    drained++;
                    log.info("Backlog drainer re-dispatched conversation {} after worker {} came online",
                            conv.getId(), event.agentInstanceId());
                    // One newly-available worker serves one turn; stop here so
                    // the remaining backlog drains as more capacity frees up.
                    return;
                }
            } catch (Exception e) {
                log.warn("Backlog drainer skipped conversation {} ({})",
                        conv.getId(), e.getMessage());
            }
        }
        log.debug("Backlog drainer found no dispatchable pending turn for host {} (worker {}); drained {}",
                hostId, event.agentInstanceId(), drained);
    }

    /**
     * Whether a conversation has an unanswered USER turn that no worker is
     * currently serving. Pending = the latest active, non-notice message is a
     * USER message AND no worker holds an active binding to the conversation
     * (the latter guards against re-dispatching an in-flight turn).
     */
    private boolean hasPendingTurn(UUID conversationId) {
        if (agentInstanceRepository.countByConversationIdAndStatusIn(
                conversationId, ACTIVE_BINDING_STATUSES) > 0) {
            return false; // a worker is already on this turn
        }
        List<ConversationMessage> messages = conversationService.listMessages(conversationId);
        for (int i = messages.size() - 1; i >= 0; i--) {
            ConversationMessage m = messages.get(i);
            if (m.isSuperseded() || conversationNoticeService.isNoAgentNotice(m)) {
                continue;
            }
            return m.getRole() == ConversationMessage.Role.USER;
        }
        return false;
    }
}
