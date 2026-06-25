// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.attachment;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.DuplicateResourceException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.attachment.dto.PromoteAttachmentResponse;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationRepository;
import ai.myrmec.engine.knowledge.rag.KnowledgeBase;
import ai.myrmec.engine.knowledge.rag.KnowledgeBaseRepository;
import ai.myrmec.engine.knowledge.rag.KnowledgeBaseService;
import ai.myrmec.engine.knowledge.rag.KnowledgeChunk;
import ai.myrmec.engine.knowledge.rag.KnowledgeChunkRepository;
import ai.myrmec.engine.knowledge.rag.KnowledgeSource;
import ai.myrmec.engine.knowledge.rag.KnowledgeSourceRepository;
import ai.myrmec.engine.knowledge.rag.StubRetrievalProvider;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service-level test for promote-to-KB (#103-C). Drives
 * {@link AttachmentPromotionService} directly with rows persisted through the
 * repositories, covering the happy path (source + chunks created), the
 * scan-clean gate, the extractable-content gate, and idempotency.
 */
@Transactional
class AttachmentPromotionServiceTest extends IntegrationTestBase {

    @Autowired
    private AttachmentPromotionService promotionService;

    @Autowired
    private ConversationMessageAttachmentRepository attachmentRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Autowired
    private KnowledgeSourceRepository knowledgeSourceRepository;

    @Autowired
    private KnowledgeChunkRepository knowledgeChunkRepository;

    @Autowired
    private AttachmentPromotionRepository promotionRepository;

    @Autowired
    private TestDataBuilder data;

    private record Fixture(UUID projectId, UUID conversationId, UUID knowledgeBaseId) {}

    private Fixture fixture(String slug) {
        Project project = data.project().named("promote-" + slug).create();
        Conversation conversation = new Conversation();
        conversation.setProjectId(project.getId());
        conversation.setTitle("Promote " + slug);
        conversation = conversationRepository.save(conversation);
        KnowledgeBase kb = knowledgeBaseService.createProjectBase(
                project.getId(), "kb-" + slug, "Target KB",
                StubRetrievalProvider.PROVIDER_ID, null);
        return new Fixture(project.getId(), conversation.getId(), kb.getId());
    }

    private UUID attachment(UUID conversationId, AttachmentScanStatus status, String extracted) {
        ConversationMessageAttachment a = new ConversationMessageAttachment();
        a.setConversationId(conversationId);
        a.setUploadedBy(TEST_ADMIN_ID);
        a.setFilename("notes.txt");
        a.setMediaType("text/plain");
        a.setSizeBytes(extracted == null ? 0 : extracted.length());
        a.setScanStatus(status);
        if (status == AttachmentScanStatus.CLEAN) {
            a.setSha256("a".repeat(64));
            a.setStorageKey("blob/" + UUID.randomUUID());
        }
        a.setExtractedText(extracted);
        return attachmentRepository.save(a).getId();
    }

    @Test
    void cleanAttachmentIsPromotedAndIngestedAsChunks() {
        Fixture f = fixture("happy");
        UUID attachmentId = attachment(f.conversationId(), AttachmentScanStatus.CLEAN,
                "Myrmec promotes scan-clean attachments into knowledge bases.");

        PromoteAttachmentResponse response = promotionService.promote(
                f.projectId(), f.conversationId(), attachmentId, f.knowledgeBaseId(), null, TEST_ADMIN_ID);

        assertThat(response.status()).isEqualTo("INGESTING");
        assertThat(response.knowledgeSourceId()).isNotNull();

        // A source was created under the KB and chunked into knowledge_chunks.
        KnowledgeSource source = knowledgeSourceRepository.findById(response.knowledgeSourceId()).orElseThrow();
        assertThat(source.getKnowledgeBaseId()).isEqualTo(f.knowledgeBaseId());
        List<KnowledgeChunk> chunks = knowledgeChunkRepository.findByKnowledgeSourceId(source.getId());
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(c -> assertThat(c.getContent()).isNotBlank());

        // The promotion link row is recorded, and the KB is left ACTIVE.
        assertThat(promotionRepository.existsByAttachmentIdAndKnowledgeBaseId(attachmentId, f.knowledgeBaseId()))
                .isTrue();
        KnowledgeBase kb = knowledgeBaseRepository.findById(f.knowledgeBaseId()).orElseThrow();
        assertThat(kb.getStatus()).isEqualTo(KnowledgeBase.Status.ACTIVE);
    }

    @Test
    void quarantinedAttachmentIsRejected() {
        Fixture f = fixture("infected");
        UUID attachmentId = attachment(f.conversationId(), AttachmentScanStatus.INFECTED, null);

        assertThatThrownBy(() -> promotionService.promote(
                f.projectId(), f.conversationId(), attachmentId, f.knowledgeBaseId(), null, TEST_ADMIN_ID))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void attachmentWithNoExtractableTextIsRejected() {
        Fixture f = fixture("notext");
        UUID attachmentId = attachment(f.conversationId(), AttachmentScanStatus.CLEAN, "   ");

        assertThatThrownBy(() -> promotionService.promote(
                f.projectId(), f.conversationId(), attachmentId, f.knowledgeBaseId(), null, TEST_ADMIN_ID))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void unknownKnowledgeBaseIsNotFound() {
        Fixture f = fixture("unknownkb");
        UUID attachmentId = attachment(f.conversationId(), AttachmentScanStatus.CLEAN, "Some content.");

        assertThatThrownBy(() -> promotionService.promote(
                f.projectId(), f.conversationId(), attachmentId, UUID.randomUUID(), null, TEST_ADMIN_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void promotingTwiceIntoSameBaseIsDuplicate() {
        Fixture f = fixture("dup");
        UUID attachmentId = attachment(f.conversationId(), AttachmentScanStatus.CLEAN, "Repeat content.");

        promotionService.promote(f.projectId(), f.conversationId(), attachmentId,
                f.knowledgeBaseId(), null, TEST_ADMIN_ID);

        assertThatThrownBy(() -> promotionService.promote(
                f.projectId(), f.conversationId(), attachmentId, f.knowledgeBaseId(), null, TEST_ADMIN_ID))
                .isInstanceOf(DuplicateResourceException.class);
    }
}
