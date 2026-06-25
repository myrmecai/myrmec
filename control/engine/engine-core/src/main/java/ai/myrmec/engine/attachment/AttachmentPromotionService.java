// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.attachment;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.DuplicateResourceException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.attachment.dto.PromoteAttachmentResponse;
import ai.myrmec.engine.audit.AuditLogService;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationRepository;
import ai.myrmec.engine.knowledge.rag.ConnectorDispatcher;
import ai.myrmec.engine.knowledge.rag.KnowledgeBase;
import ai.myrmec.engine.knowledge.rag.KnowledgeBaseRepository;
import ai.myrmec.engine.knowledge.rag.KnowledgeBaseService;
import ai.myrmec.engine.knowledge.rag.KnowledgeSource;
import ai.myrmec.engine.knowledge.rag.ManualConnector;
import ai.myrmec.engine.spi.connector.ConnectorException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Promote-to-KB orchestration (#103-C). Turns a scan-clean conversation
 * attachment into a durable, retrievable {@code knowledge_sources} row by
 * staging its extracted text through the {@code manual} connector and running a
 * synchronous sync, so the content is chunked and embedded exactly like any
 * other knowledge-base source.
 *
 * <p>The action is idempotent per {@code (attachment, knowledge base)}: a
 * repeat promotion is rejected with {@code 409 DUPLICATE_CODE} via
 * {@link AttachmentPromotion}'s unique constraint and the pre-check here.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AttachmentPromotionService {

    /** Status reported to the UI while ingestion completes (UC-014 A4f). */
    private static final String STATUS_INGESTING = "INGESTING";

    private final AttachmentService attachmentService;
    private final ConversationRepository conversationRepository;
    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final ManualConnector manualConnector;
    private final ConnectorDispatcher connectorDispatcher;
    private final AttachmentPromotionRepository promotionRepository;
    private final AuditLogService auditLogService;

    /**
     * Promote {@code attachmentId} (which must belong to {@code conversationId},
     * itself owned by {@code projectId}) into the project knowledge base
     * {@code knowledgeBaseId}.
     *
     * @throws ResourceNotFoundException attachment/conversation/KB not found, or
     *         not reachable from {@code projectId} (404).
     * @throws BadRequestException       attachment is not scan-clean, or has no
     *         extractable text content (400).
     * @throws DuplicateResourceException attachment already promoted into this
     *         KB (409 DUPLICATE_CODE).
     */
    public PromoteAttachmentResponse promote(UUID projectId,
                                             UUID conversationId,
                                             UUID attachmentId,
                                             UUID knowledgeBaseId,
                                             String sourceName,
                                             UUID actorUserId) {
        // 404 if the attachment is not bound to this conversation.
        ConversationMessageAttachment attachment =
                attachmentService.require(conversationId, attachmentId);

        // 404 if the conversation is not owned by this project (no cross-project probing).
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", conversationId));
        if (!projectId.equals(conversation.getProjectId())) {
            throw new ResourceNotFoundException("Conversation", conversationId);
        }

        // 400 if not scan-clean: quarantined content must never reach a KB.
        if (attachment.getScanStatus() != AttachmentScanStatus.CLEAN) {
            throw BadRequestException.forField("attachmentId", "NOT_SCAN_CLEAN",
                    "Attachment is not scan-clean and cannot be promoted.");
        }

        // 400 if there is no extractable text (e.g. an image with no OCR text).
        String extracted = attachment.getExtractedText();
        if (extracted == null || extracted.isBlank()) {
            throw BadRequestException.forField("attachmentId", "NO_EXTRACTABLE_CONTENT",
                    "Attachment has no extractable text content to ingest.");
        }

        // 404 if the target KB is not a PROJECT base owned by this project.
        KnowledgeBase kb = knowledgeBaseService.getProjectBase(projectId, knowledgeBaseId);

        // 409 if this attachment was already promoted into this KB.
        if (promotionRepository.existsByAttachmentIdAndKnowledgeBaseId(attachmentId, knowledgeBaseId)) {
            throw new DuplicateResourceException(
                    "Attachment", "attachmentId", attachmentId + "->" + knowledgeBaseId);
        }

        // Create the source under a name unique within the KB.
        String name = uniqueSourceName(knowledgeBaseId,
                (sourceName != null && !sourceName.isBlank()) ? sourceName.trim() : attachment.getFilename());
        KnowledgeSource source = knowledgeBaseService.addSource(
                knowledgeBaseId,
                ManualConnector.CONNECTOR_TYPE,
                name,
                "attachment://" + attachmentId,
                null,
                null);

        // Stage the extracted text as a single chunk, then run a synchronous
        // sync so it is chunked + embedded into knowledge_chunks.
        manualConnector.stage(source.getId(), attachmentId.toString(), extracted,
                attachmentMetadata(attachment));

        kb.setStatus(KnowledgeBase.Status.SYNCING);
        knowledgeBaseRepository.save(kb);
        try {
            connectorDispatcher.sync(source.getId());
        } catch (ConnectorException e) {
            throw new IllegalStateException(
                    "Failed to ingest promoted attachment " + attachmentId, e);
        }
        kb.setStatus(KnowledgeBase.Status.ACTIVE);
        knowledgeBaseRepository.save(kb);

        AttachmentPromotion promotion = new AttachmentPromotion();
        promotion.setAttachmentId(attachmentId);
        promotion.setKnowledgeBaseId(knowledgeBaseId);
        promotion.setKnowledgeSourceId(source.getId());
        promotion.setPromotedBy(actorUserId);
        promotionRepository.save(promotion);

        audit(conversationId, attachmentId, knowledgeBaseId, source.getId(), actorUserId);

        log.info("Promoted attachment {} into knowledge base {} as source {}",
                attachmentId, knowledgeBaseId, source.getId());
        return new PromoteAttachmentResponse(source.getId(), STATUS_INGESTING);
    }

    /** Non-null string metadata for the staged chunk. */
    private static Map<String, String> attachmentMetadata(ConversationMessageAttachment a) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("attachmentId", a.getId().toString());
        meta.put("filename", a.getFilename());
        meta.put("mediaType", a.getMediaType());
        if (a.getSha256() != null) {
            meta.put("sha256", a.getSha256());
        }
        return meta;
    }

    /**
     * Derive a source name unique within the KB, suffixing {@code (2)},
     * {@code (3)} … on collision so a name clash does not masquerade as a
     * "already promoted" 409.
     */
    private String uniqueSourceName(UUID knowledgeBaseId, String desired) {
        Set<String> existing = knowledgeBaseService.listSources(knowledgeBaseId).stream()
                .map(KnowledgeSource::getName)
                .collect(Collectors.toSet());
        if (!existing.contains(desired)) {
            return desired;
        }
        for (int i = 2; ; i++) {
            String candidate = desired + " (" + i + ")";
            if (!existing.contains(candidate)) {
                return candidate;
            }
        }
    }

    private void audit(UUID conversationId, UUID attachmentId, UUID knowledgeBaseId,
                       UUID sourceId, UUID actorUserId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId.toString());
        payload.put("knowledgeBaseId", knowledgeBaseId.toString());
        payload.put("knowledgeSourceId", sourceId.toString());
        auditLogService.record(AuditLogService.AuditEvent.builder()
                .action("ATTACHMENT_PROMOTED")
                .actorUserId(actorUserId)
                .resourceType("ConversationMessageAttachment")
                .resourceId(attachmentId)
                .scopeType("CONVERSATION")
                .scopeId(conversationId)
                .payload(payload)
                .build());
    }
}
