package ai.myrmec.engine.assistant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Assistant — the parent identity row of the two-table Assistant model
 * (see {@code docs/design/assistant-entity.md} §3).
 *
 * <p>Carries cosmetic fields (name, description) and control-plane kill
 * switches ({@code disabled}, {@code archivedAt}) that are edited in place
 * without bumping a version. Runtime behaviour lives entirely on
 * {@link AssistantVersion}; {@code currentVersionId} points at the row every
 * new session pins.</p>
 */
@Entity
@Table(name = "assistants")
@Getter
@Setter
@NoArgsConstructor
public class Assistant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    /** Currently-Published {@link AssistantVersion}. Null until first publish. */
    @Column(name = "current_version_id")
    private UUID currentVersionId;

    /** Kill switch: new sessions rejected 403; existing sessions continue. Reversible. */
    @Column(name = "disabled", nullable = false)
    private boolean disabled = false;

    /** Soft delete: non-null hides from listings. No hard delete in V1; reversible by admin. */
    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
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
