package ai.myrmec.engine.conversation;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, UUID> {

    List<ConversationMessage> findByConversationIdOrderBySequenceNoAsc(UUID conversationId);

    /**
     * Scrollback page (newest first). Returns the newest {@code limit}
     * messages of a conversation; the service reverses them to ascending
     * order before returning to callers.
     */
    List<ConversationMessage> findByConversationIdOrderBySequenceNoDesc(
            UUID conversationId, Pageable pageable);

    /**
     * Scrollback page strictly older than a cursor (newest first). Returns
     * the newest {@code limit} messages whose {@code sequence_no} is below
     * {@code before}; the service reverses them to ascending order.
     */
    List<ConversationMessage> findByConversationIdAndSequenceNoLessThanOrderBySequenceNoDesc(
            UUID conversationId, long before, Pageable pageable);

    /**
     * Used by {@code ConversationMessageService} to assign the next
     * {@code sequence_no} for a conversation. Returns the highest
     * sequence_no currently persisted, or empty for a fresh conversation.
     */
    Optional<ConversationMessage> findFirstByConversationIdOrderBySequenceNoDesc(UUID conversationId);

    long countByConversationId(UUID conversationId);

    /**
     * #104b — the active-or-superseded rows at and beyond a sequence point,
     * ascending. Used by edit-resend / regenerate to soft-supersede the tail
     * of the active branch from the edited / regenerated turn onward.
     */
    List<ConversationMessage> findByConversationIdAndSequenceNoGreaterThanEqualOrderBySequenceNoAsc(
            UUID conversationId, long sequenceNo);

    /**
     * #95 External API long-poll — rows strictly newer than a cursor,
     * ascending. The {@code GET /external/sessions/{id}/messages?since=N}
     * endpoint returns messages whose {@code sequence_no > N} so a caller can
     * incrementally fetch new turns.
     */
    List<ConversationMessage> findByConversationIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
            UUID conversationId, long sequenceNo);

    /**
     * Phase 7e — sweeper input. APPROVAL_REQUEST rows still PENDING past
     * their {@code expiresAt} that need flipping to EXPIRED in a
     * dedicated transaction.
     */
    List<ConversationMessage> findByRoleAndApprovalStatusAndExpiresAtBefore(
            ConversationMessage.Role role,
            ConversationMessage.ApprovalStatus approvalStatus,
            Instant cutoff);

    /**
     * UC-013 My Work Approvals tab — every message row of a given role and
     * approval status across all conversations (e.g. all still-PENDING
     * APPROVAL_REQUEST rows). The My Work service filters the result down to
     * the caller's accessible projects in memory (admin-scale).
     */
    List<ConversationMessage> findByRoleAndApprovalStatus(
            ConversationMessage.Role role,
            ConversationMessage.ApprovalStatus approvalStatus);
}
