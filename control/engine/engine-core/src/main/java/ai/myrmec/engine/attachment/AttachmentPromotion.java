// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.attachment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Link record for a promote-to-KB action (#103-C). Records that a scan-clean
 * {@link ConversationMessageAttachment} was ingested into a knowledge base as a
 * durable {@code knowledge_sources} row.
 *
 * <p>The unique {@code (attachment_id, knowledge_base_id)} constraint makes
 * promotion idempotent: a second attempt to promote the same attachment into
 * the same KB is rejected with {@code 409 DUPLICATE_CODE} rather than creating
 * a duplicate source.</p>
 */
@Entity
@Table(name = "attachment_promotions")
@Getter
@Setter
@NoArgsConstructor
public class AttachmentPromotion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "attachment_id", nullable = false)
    private UUID attachmentId;

    @Column(name = "knowledge_base_id", nullable = false)
    private UUID knowledgeBaseId;

    @Column(name = "knowledge_source_id", nullable = false)
    private UUID knowledgeSourceId;

    @Column(name = "promoted_by")
    private UUID promotedBy;

    @Column(name = "promoted_at", nullable = false, updatable = false)
    private Instant promotedAt;

    @PrePersist
    void onCreate() {
        promotedAt = Instant.now();
    }
}
