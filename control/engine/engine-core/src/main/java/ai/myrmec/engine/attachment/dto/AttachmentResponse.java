package ai.myrmec.engine.attachment.dto;

import ai.myrmec.engine.attachment.ConversationMessageAttachment;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model for a conversation attachment (#103). Never exposes the storage
 * key or extracted text — only client-relevant metadata + scan status.
 */
public record AttachmentResponse(
        UUID id,
        UUID conversationId,
        UUID messageId,
        UUID uploadedBy,
        String filename,
        String mediaType,
        long sizeBytes,
        String sha256,
        String scanStatus,
        String scanThreat,
        Instant createdAt
) {
    public static AttachmentResponse from(ConversationMessageAttachment a) {
        return new AttachmentResponse(
                a.getId(),
                a.getConversationId(),
                a.getMessageId(),
                a.getUploadedBy(),
                a.getFilename(),
                a.getMediaType(),
                a.getSizeBytes(),
                a.getSha256(),
                a.getScanStatus() == null ? null : a.getScanStatus().name(),
                a.getScanThreat(),
                a.getCreatedAt()
        );
    }
}
