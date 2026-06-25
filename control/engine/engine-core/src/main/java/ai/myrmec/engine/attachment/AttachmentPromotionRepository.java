// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.attachment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AttachmentPromotionRepository
        extends JpaRepository<AttachmentPromotion, UUID> {

    /**
     * Idempotency guard for promote-to-KB (#103-C): true once an attachment has
     * already been promoted into the given knowledge base.
     */
    boolean existsByAttachmentIdAndKnowledgeBaseId(UUID attachmentId, UUID knowledgeBaseId);
}
