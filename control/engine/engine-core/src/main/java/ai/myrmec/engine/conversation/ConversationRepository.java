package ai.myrmec.engine.conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    List<Conversation> findByProjectIdOrderByUpdatedAtDesc(UUID projectId);

    List<Conversation> findByCreatedByOrderByUpdatedAtDesc(UUID createdBy);
}
