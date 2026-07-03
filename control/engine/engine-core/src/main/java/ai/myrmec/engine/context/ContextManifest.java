// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.context;

import ai.myrmec.engine._system.common.JsonListMapConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Context Manifest — per-turn context assembly audit record.
 *
 * <p>Written by the Context Builder on every turn. Records what was
 * included, excluded, and truncated in the assembled context and why.
 * Read by UC-KM-12 (Context Audit) and the Session Replay timeline.</p>
 *
 * <p>Chunk content is NOT duplicated here — it lives in {@code knowledge_chunks}.
 * Citations are stored in {@code conversation_messages.payload_json}.</p>
 */
@Entity
@Table(name = "context_manifests")
@Getter
@Setter
@NoArgsConstructor
public class ContextManifest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "message_id")
    private UUID messageId;

    @Column(name = "sequence_no", nullable = false)
    private Long sequenceNo;

    @Column(name = "governance_profile_code", nullable = false, length = 50)
    private String governanceProfileCode;

    @Column(name = "context_pinning", nullable = false, length = 20)
    private String contextPinning;

    @Column(name = "total_tokens", nullable = false)
    private Integer totalTokens;

    @Column(name = "budget_tokens", nullable = false)
    private Integer budgetTokens;

    @Column(name = "truncated", nullable = false)
    private Boolean truncated;

    @Convert(converter = JsonListMapConverter.class)
    @Column(name = "instructions_included", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> instructionsIncluded;

    @Convert(converter = JsonListMapConverter.class)
    @Column(name = "instructions_excluded", columnDefinition = "jsonb")
    private List<Map<String, Object>> instructionsExcluded;

    @Convert(converter = JsonListMapConverter.class)
    @Column(name = "instructions_truncated", columnDefinition = "jsonb")
    private List<Map<String, Object>> instructionsTruncated;

    @Convert(converter = JsonListMapConverter.class)
    @Column(name = "knowledge_retrieved", columnDefinition = "jsonb")
    private List<Map<String, Object>> knowledgeRetrieved;

    @Convert(converter = JsonListMapConverter.class)
    @Column(name = "knowledge_included", columnDefinition = "jsonb")
    private List<Map<String, Object>> knowledgeIncluded;

    @Convert(converter = JsonListMapConverter.class)
    @Column(name = "knowledge_truncated", columnDefinition = "jsonb")
    private List<Map<String, Object>> knowledgeTruncated;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}