package ai.myrmec.engine.knowledge.rag;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Knowledge chunk — one indexable unit produced by a {@link KnowledgeSource}
 * sync. Engine stores text + locator + metadata; embeddings live in the
 * retrieval provider's vector store so different providers can use different
 * embedding models on the same source content.
 */
@Entity
@Table(name = "knowledge_chunks")
@Getter
@Setter
@NoArgsConstructor
public class KnowledgeChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "knowledge_source_id", nullable = false)
    private UUID knowledgeSourceId;

    @Column(name = "locator", nullable = false, length = 2000)
    private String locator;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    /** SHA-256 hex of {@link #content}; lets connectors skip rewrites on no-op re-syncs. */
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "metadata_json", columnDefinition = "text")
    private String metadataJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
