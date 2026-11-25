package ai.myrmec.engine.conversation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Appends transitions to the immutable {@link ConversationEvent} log
 * (conversation-observability §4).
 *
 * <p>With the warm-worker {@code agents} model removed (the session is now
 * the runtime span), the bind-lifecycle {@code record(...)} path is gone.
 * What remains is the replay accessor.</p>
 */
@Service
@Slf4j
public class ConversationEventService {

    private final ConversationEventRepository repository;

    public ConversationEventService(ConversationEventRepository repository) {
        this.repository = repository;
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
