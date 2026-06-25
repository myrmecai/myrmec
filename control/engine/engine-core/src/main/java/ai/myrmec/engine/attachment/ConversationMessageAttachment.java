package ai.myrmec.engine.attachment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * An uploaded file attached to a conversation (#103). Attachments are
 * ephemeral-to-conversation by default: created against a {@code conversation}
 * and (once the user sends the turn that references them) bound to a
 * {@code conversation_messages} row via {@link #messageId}.
 *
 * <p>The row carries metadata + a {@link #storageKey} into the configured
 * {@code BlobStore}; the bytes themselves never live in the database. Only
 * {@link AttachmentScanStatus#CLEAN} rows have a {@code storageKey} — an
 * infected / errored upload is retained for transparency but quarantined
 * (no bytes stored, never exposed to an agent — #104).</p>
 */
@Entity
@Table(name = "conversation_message_attachments")
@Getter
@Setter
@NoArgsConstructor
public class ConversationMessageAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    /**
     * The message this attachment is bound to, set when the referencing USER
     * turn is posted. Null while the attachment is freshly uploaded and not
     * yet attached to a sent message.
     */
    @Column(name = "message_id")
    private UUID messageId;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    @Column(name = "filename", nullable = false, length = 512)
    private String filename;

    @Column(name = "media_type", nullable = false, length = 150)
    private String mediaType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** Lowercase hex SHA-256 of the stored bytes. Null when quarantined. */
    @Column(name = "sha256", length = 64)
    private String sha256;

    /** Key into the {@code BlobStore}. Null when quarantined (no bytes stored). */
    @Column(name = "storage_key", length = 512)
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "scan_status", nullable = false, length = 20)
    private AttachmentScanStatus scanStatus;

    /** Identifier of the scanner that produced {@link #scanStatus}. */
    @Column(name = "scan_provider", length = 100)
    private String scanProvider;

    /** Detected signature / failure cause; null for a clean attachment. */
    @Column(name = "scan_threat", length = 255)
    private String scanThreat;

    /**
     * Text extracted from the attachment for inline context injection (#103,
     * filled by the extraction pass). Null until extracted / for binary
     * (image) attachments.
     */
    @Column(name = "extracted_text", columnDefinition = "text")
    private String extractedText;

    /** Rough token estimate of {@link #extractedText}, for the inline/tool split. */
    @Column(name = "token_estimate")
    private Integer tokenEstimate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
