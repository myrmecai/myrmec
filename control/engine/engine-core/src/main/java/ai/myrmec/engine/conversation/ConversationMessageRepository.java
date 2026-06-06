package ai.myrmec.engine.conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, UUID> {

    List<ConversationMessage> findByConversationIdOrderBySequenceNoAsc(UUID conversationId);

    /**
     * Used by {@code ConversationMessageService} to assign the next
     * {@code sequence_no} for a conversation. Returns the highest
     * sequence_no currently persisted, or empty for a fresh conversation.
     */
    Optional<ConversationMessage> findFirstByConversationIdOrderBySequenceNoDesc(UUID conversationId);

    long countByConversationId(UUID conversationId);
}
