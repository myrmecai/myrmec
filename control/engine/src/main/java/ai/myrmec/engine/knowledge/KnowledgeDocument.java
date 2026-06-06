package ai.myrmec.engine.knowledge;

import ai.myrmec.engine._system.common.JsonListConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Knowledge document entity.
 * Stores organizational standards, project instructions, requirements, and architecture docs
 * that are injected into agent task context.
 */
@Entity
@Table(name = "knowledge_documents")
@Getter
@Setter
@NoArgsConstructor
public class KnowledgeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Scope of this document: ORGANIZATION (all projects) or PROJECT (specific project).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20)
    private KnowledgeScope scope;

    /**
     * Project this document belongs to (null for ORGANIZATION scope).
     */
    @Column(name = "project_id")
    private UUID projectId;

    /**
     * Category for filtering and display.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private KnowledgeCategory category;

    /**
     * Human-readable name for the document.
     */
    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /**
     * Markdown content of the document.
     */
    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    /**
     * Priority for inclusion in context (higher = included first).
     * Default is 100. Critical standards might be 200+, supplementary docs might be 50.
     */
    @Column(name = "priority", nullable = false)
    private int priority = 100;

    /**
     * Glob patterns for file-specific knowledge filtering.
     * Example: ["**\/*.java", "agents/**"] means this doc applies when working on Java files or agent code.
     * Null means applies to all files.
     */
    @Convert(converter = JsonListConverter.class)
    @Column(name = "applies_to")
    private List<String> appliesTo;

    /**
     * Original file path if this document was synced from a repository.
     * Example: ".github/instructions/engine-standards.md"
     */
    @Column(name = "source_path", length = 500)
    private String sourcePath;

    /**
     * Whether this document is active and should be included in context resolution.
     */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    /**
     * User who created this document (null for system-generated).
     */
    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
