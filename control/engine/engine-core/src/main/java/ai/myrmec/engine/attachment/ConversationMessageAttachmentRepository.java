package ai.myrmec.engine.attachment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ConversationMessageAttachmentRepository
        extends JpaRepository<ConversationMessageAttachment, UUID> {

    /** All attachments for a conversation, newest last. */
    List<ConversationMessageAttachment> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    /** Attachments bound to a specific message. */
    List<ConversationMessageAttachment> findByMessageIdOrderByCreatedAtAsc(UUID messageId);

    /**
     * Freshly uploaded, scan-clean attachments for a conversation that are not
     * yet bound to a message — the candidates to attach to the next sent turn.
     */
    List<ConversationMessageAttachment>
            findByConversationIdAndMessageIdIsNullAndScanStatusOrderByCreatedAtAsc(
                    UUID conversationId, AttachmentScanStatus scanStatus);
}
