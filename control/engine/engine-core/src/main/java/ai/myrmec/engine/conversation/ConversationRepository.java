package ai.myrmec.engine.conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    List<Conversation> findByTeamIdOrderByUpdatedAtDesc(UUID teamId);

    List<Conversation> findByCreatedByOrderByUpdatedAtDesc(UUID createdBy);

    /**
     * #95 External API — every session opened by a service account, most
     * recently updated first. Used by {@code GET /external/sessions} when no
     * {@code externalUserRef} filter is supplied.
     */
    List<Conversation> findByServiceAccountIdOrderByUpdatedAtDesc(UUID serviceAccountId);

    /**
     * #95 External API — a service account's sessions for one opaque end-user
     * reference ({@code X-Myrmec-End-User-Ref}), most recently updated first.
     * Used by {@code GET /external/sessions?externalUserRef=...} so a caller
     * can resume an end user's prior threads.
     */
    List<Conversation> findByServiceAccountIdAndExternalUserRefOrderByUpdatedAtDesc(
            UUID serviceAccountId, String externalUserRef);
}
