package ai.myrmec.engine.conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Repository for the append-only {@link ConversationEvent} log. Reads support
 * per-conversation replay (ordered by {@code seq}) and the sequence /
 * bind-attempt derivation the {@link ConversationEventService} needs when
 * appending a new event.
 */
public interface ConversationEventRepository extends JpaRepository<ConversationEvent, UUID> {

    /**
     * Highest {@code seq} recorded for a conversation, or {@code -1} when the
     * conversation has no events yet (so the first {@code seq} is {@code 0}).
     */
    @Query("SELECT COALESCE(MAX(e.seq), -1) FROM ConversationEvent e "
            + "WHERE e.conversationId = :conversationId")
    int findMaxSeq(@Param("conversationId") UUID conversationId);

    /** How many connect attempts (RESERVED events) a conversation has had. */
    long countByConversationIdAndReasonCode(UUID conversationId, ConversationEventReason reasonCode);

    /** Replay a conversation's lifecycle in order. */
    List<ConversationEvent> findByConversationIdOrderBySeqAsc(UUID conversationId);
}
