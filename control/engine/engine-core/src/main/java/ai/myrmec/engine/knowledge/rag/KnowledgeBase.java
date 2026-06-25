package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine.spi.retrieval.KnowledgeBaseScope;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Knowledge base — the unit of RAG visibility + provider routing.
 *
 * <p>Each KB is owned by exactly one of: a project (PROJECT scope), a group
 * (GROUP scope), or the system as a whole (SYSTEM scope). The CHECK constraint
 * on the underlying table enforces the project_id / group_id / scope tuple.
 * Engine resolves the retrieval provider at query time via {@link #providerId}
 * matching {@code RetrievalProvider.id()}.</p>
 */
@Entity
@Table(name = "knowledge_bases")
@Getter
@Setter
@NoArgsConstructor
public class KnowledgeBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20)
    private KnowledgeBaseScope scope;

    /** Set when scope = PROJECT; null otherwise. */
    @Column(name = "project_id")
    private UUID projectId;

    /** Set when scope = GROUP; null otherwise. */
    @Column(name = "group_id")
    private UUID groupId;

    /** Matches {@code RetrievalProvider.id()}. */
    @Column(name = "provider_id", nullable = false, length = 100)
    private String providerId;

    /** Provider-specific JSON config (collection name, top-k defaults, ...). */
    @Column(name = "provider_config", columnDefinition = "text")
    private String providerConfig;

    /** Lifecycle status (#92, §4.4). Non-ACTIVE KBs are greyed in the Assistant KB picker. */
    public enum Status { ACTIVE, SYNCING, DISABLED, ARCHIVED }

    /** Pinned embedding model for chunk vectors; null until first embed run. */
    @Column(name = "embedding_model", length = 80)
    private String embeddingModel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    /** Free-form classification label (V1 schema seam; V1.1 ACL enforcement #33). */
    @Column(name = "classification", length = 40)
    private String classification;

    /** Platform/admin can hide a KB from Assistant designers even if it exists. */
    @Column(name = "allow_assistant_binding", nullable = false)
    private boolean allowAssistantBinding = true;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
