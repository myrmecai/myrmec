package ai.myrmec.engine.conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConversationParticipantRepository
        extends JpaRepository<ConversationParticipant, UUID> {

    List<ConversationParticipant> findByConversationId(UUID conversationId);

    List<ConversationParticipant> findByUserId(UUID userId);

    Optional<ConversationParticipant> findByConversationIdAndUserId(UUID conversationId, UUID userId);
}
