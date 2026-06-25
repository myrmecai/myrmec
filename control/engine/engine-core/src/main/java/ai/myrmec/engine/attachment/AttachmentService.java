package ai.myrmec.engine.attachment;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.audit.AuditLogService;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationRepository;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectRepository;
import ai.myrmec.engine.setting.SystemSettingService;
import ai.myrmec.engine.spi.scan.ContentScanProvider;
import ai.myrmec.engine.spi.scan.ScanResult;
import ai.myrmec.engine.spi.storage.BlobStore;
import ai.myrmec.engine.spi.storage.StoredBlob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Upload + lifecycle service for conversation attachments (#103) with the
 * mandatory scan gate (#104). Every upload is validated (size cap + MIME
 * allowlist), scanned <em>before</em> any bytes are stored, and only stored
 * when the scan is clean. Infected / errored uploads are quarantined: the
 * metadata row is retained for transparency + audit, no bytes are stored, and
 * the attachment is never exposed to an agent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentService {

    /** Image MIME types (rendered as native image parts for vision models). */
    static final Set<String> IMAGE_TYPES = Set.of(
            "image/png", "image/jpeg", "image/webp");

    /** Office document MIME types (text extracted downstream). */
    private static final Set<String> OFFICE_TYPES = Set.of(
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation");

    /** Non-text, non-office single types that are explicitly allowed. */
    private static final Set<String> OTHER_DOCUMENT_TYPES = Set.of(
            "application/pdf", "application/json", "application/xml");

    private static final String MAX_SIZE_KEY = "attachment_max_file_size_bytes";
    private static final long MAX_SIZE_DEFAULT = 10L * 1024 * 1024;

    private final ConversationMessageAttachmentRepository attachmentRepository;
    private final ConversationRepository conversationRepository;
    private final ProjectRepository projectRepository;
    private final BlobStore blobStore;
    private final ContentScanProvider scanProvider;
    private final SystemSettingService systemSettingService;
    private final AuditLogService auditLogService;

    /**
     * Validate, scan-gate, and (when clean) store an uploaded attachment
     * against a conversation. Returns the persisted row. A scan failure
     * persists a quarantined row and throws {@link BadRequestException}.
     *
     * <p>Intentionally <em>not</em> wrapped in a single transaction: the
     * quarantine record must survive the {@link BadRequestException} that
     * rejects the upload (a method-level rollback would erase the evidence
     * trail), so each persistence step commits on its own.</p>
     */
    public ConversationMessageAttachment upload(
            UUID conversationId, MultipartFile file, UUID actorUserId) {
        Conversation conversation = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new ResourceNotFoundException("Conversation", conversationId));
        Project project = projectRepository.findById(conversation.getProjectId())
            .orElseThrow(() -> ResourceNotFoundException.of(
                "Project", "id", String.valueOf(conversation.getProjectId())));
        if (!project.isAttachmentsEnabled()) {
            throw BadRequestException.forField("file", "ATTACHMENTS_DISABLED",
                "Attachments are disabled for this project.");
        }
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Attachment file must not be empty");
        }

        long maxSize = project.getAttachmentMaxFileSizeBytes() != null
            ? project.getAttachmentMaxFileSizeBytes()
            : systemSettingService.getInt(MAX_SIZE_KEY, MAX_SIZE_DEFAULT);
        if (file.getSize() > maxSize) {
            throw BadRequestException.forField("file", "TOO_LARGE",
                    "Attachment exceeds the maximum size of " + maxSize + " bytes.");
        }

        String mediaType = normaliseMediaType(file.getContentType());
        if (!isAllowed(mediaType)) {
            throw BadRequestException.forField("file", "UNSUPPORTED_MEDIA_TYPE",
                    "Attachment type '" + mediaType + "' is not allowed.");
        }
        if (!isAllowedByProjectAllowlist(project.getAttachmentTypeAllowlist(), mediaType)) {
            throw BadRequestException.forField("file", "UNSUPPORTED_MEDIA_TYPE",
                "Attachment type '" + mediaType + "' is not allowed by this project's allowlist.");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded attachment", e);
        }

        String filename = sanitiseFilename(file.getOriginalFilename());

        // #104 — hard gate: scan BEFORE storing any bytes.
        ScanResult scan = scanProvider.scan(bytes, filename, mediaType);

        ConversationMessageAttachment row = new ConversationMessageAttachment();
        row.setConversationId(conversationId);
        row.setUploadedBy(actorUserId);
        row.setFilename(filename);
        row.setMediaType(mediaType);
        row.setSizeBytes(bytes.length);
        row.setScanProvider(scan.scannerId());

        if (!scan.isClean()) {
            // Quarantine: retain metadata, store nothing, never expose.
            row.setScanStatus(scan.verdict() == ai.myrmec.engine.spi.scan.ScanVerdict.INFECTED
                    ? AttachmentScanStatus.INFECTED
                    : AttachmentScanStatus.ERROR);
            row.setScanThreat(scan.threat());
            ConversationMessageAttachment quarantined = attachmentRepository.save(row);
            audit("ATTACHMENT_QUARANTINED", conversationId, quarantined.getId(), actorUserId,
                    Map.of(
                            "filename", filename,
                            "mediaType", mediaType,
                            "scanStatus", row.getScanStatus().name(),
                            "threat", String.valueOf(scan.threat())));
            throw BadRequestException.forField("file", "MALWARE_DETECTED",
                    "Attachment was blocked by the content scanner ("
                            + scan.threat() + ").");
        }

        // Clean: store bytes, persist a usable row.
        String storageKey = conversationId + "/" + UUID.randomUUID();
        StoredBlob stored = blobStore.put(storageKey, bytes, mediaType);
        row.setScanStatus(AttachmentScanStatus.CLEAN);
        row.setStorageKey(storageKey);
        row.setSha256(stored.sha256());
        try {
            AttachmentTextExtractor.extract(filename, mediaType, bytes)
                    .ifPresent(extracted -> {
                        row.setExtractedText(extracted.text());
                        row.setTokenEstimate(extracted.tokenEstimate());
                    });
        } catch (IOException ex) {
            log.warn("Attachment text extraction failed for {} ({}): {}", filename, mediaType, ex.getMessage());
        }
        ConversationMessageAttachment saved = attachmentRepository.save(row);
        audit("ATTACHMENT_UPLOADED", conversationId, saved.getId(), actorUserId,
                Map.of(
                        "filename", filename,
                        "mediaType", mediaType,
                        "sizeBytes", bytes.length,
                        "scanProvider", scan.scannerId()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<ConversationMessageAttachment> list(UUID conversationId) {
        return attachmentRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    /** Clean attachments bound to a specific message (e.g. a user turn). */
    @Transactional(readOnly = true)
    public List<ConversationMessageAttachment> listForMessage(UUID messageId) {
        return attachmentRepository.findByMessageIdOrderByCreatedAtAsc(messageId);
    }

    /**
     * Bind the conversation's freshly uploaded, scan-clean, not-yet-bound
     * attachments to a just-posted message (#103). Quarantined rows are never
     * bound. Returns the now-bound attachments (empty when there are none).
     */
    @Transactional
    public List<ConversationMessageAttachment> bindUnboundToMessage(
            UUID conversationId, UUID messageId, UUID actorUserId) {
        List<ConversationMessageAttachment> unbound = attachmentRepository
                .findByConversationIdAndMessageIdIsNullAndScanStatusOrderByCreatedAtAsc(
                        conversationId, AttachmentScanStatus.CLEAN);
        if (unbound.isEmpty()) {
            return List.of();
        }
        for (ConversationMessageAttachment row : unbound) {
            row.setMessageId(messageId);
        }
        List<ConversationMessageAttachment> bound = attachmentRepository.saveAll(unbound);
        audit("ATTACHMENT_BOUND", conversationId, messageId, actorUserId,
                Map.of("messageId", messageId.toString(), "count", bound.size()));
        return bound;
    }

    @Transactional(readOnly = true)
    public ConversationMessageAttachment require(UUID conversationId, UUID attachmentId) {
        ConversationMessageAttachment row = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Attachment", attachmentId));
        if (!conversationId.equals(row.getConversationId())) {
            throw new ResourceNotFoundException("Attachment", attachmentId);
        }
        return row;
    }

    /** Read the stored bytes of a clean attachment. */
    @Transactional(readOnly = true)
    public byte[] download(UUID conversationId, UUID attachmentId) {
        ConversationMessageAttachment row = require(conversationId, attachmentId);
        if (row.getScanStatus() != AttachmentScanStatus.CLEAN || row.getStorageKey() == null) {
            throw new BadRequestException("Attachment is not available (quarantined).");
        }
        return blobStore.get(row.getStorageKey());
    }

    @Transactional
    public void delete(UUID conversationId, UUID attachmentId, UUID actorUserId) {
        ConversationMessageAttachment row = require(conversationId, attachmentId);
        if (row.getStorageKey() != null) {
            blobStore.delete(row.getStorageKey());
        }
        attachmentRepository.delete(row);
        audit("ATTACHMENT_DELETED", conversationId, attachmentId, actorUserId,
                Map.of("filename", row.getFilename()));
    }

    static boolean isAllowed(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) {
            return false;
        }
        return mediaType.startsWith("text/")
                || OTHER_DOCUMENT_TYPES.contains(mediaType)
                || OFFICE_TYPES.contains(mediaType)
                || IMAGE_TYPES.contains(mediaType);
    }

    /** Drop charset / params and lowercase the MIME type. */
    private static String normaliseMediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "application/octet-stream";
        }
        int semi = contentType.indexOf(';');
        String base = (semi >= 0 ? contentType.substring(0, semi) : contentType).trim();
        return base.toLowerCase();
    }

    /** Strip any path components from a client-supplied filename. */
    private static String sanitiseFilename(String original) {
        if (original == null || original.isBlank()) {
            return "attachment";
        }
        String name = original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        String base = slash >= 0 ? name.substring(slash + 1) : name;
        base = base.trim();
        return base.isEmpty() ? "attachment" : base;
    }

    private static boolean isAllowedByProjectAllowlist(String allowlist, String mediaType) {
        if (allowlist == null || allowlist.isBlank()) {
            return true;
        }
        Set<String> configured = java.util.Arrays.stream(allowlist.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        return configured.stream().anyMatch(v -> Objects.equals(v, mediaType));
    }

    private void audit(String action, UUID conversationId, UUID attachmentId,
                       UUID actorUserId, Map<String, Object> payload) {
        Map<String, Object> body = new LinkedHashMap<>(payload);
        body.put("conversationId", conversationId.toString());
        auditLogService.record(AuditLogService.AuditEvent.builder()
                .action(action)
                .actorUserId(actorUserId)
                .resourceType("ConversationMessageAttachment")
                .resourceId(attachmentId)
                .scopeType("CONVERSATION")
                .scopeId(conversationId)
                .payload(body)
                .build());
    }
}
