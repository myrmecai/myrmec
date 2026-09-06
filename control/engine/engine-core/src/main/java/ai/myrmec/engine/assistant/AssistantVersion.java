package ai.myrmec.engine.assistant;

import ai.myrmec.engine._system.common.JsonListConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * AssistantVersion — the immutable-once-published behaviour contract pinned by
 * every conversation session it starts (see {@code docs/design/assistant-entity.md}
 * §3-§5).
 *
 * <p>At most one {@code DRAFT} exists per assistant (enforced by a partial
 * unique index on PostgreSQL and by {@code AssistantVersionService} for H2 /
 * defence-in-depth). Publishing freezes the row, assigns a {@code versionNumber}
 * from the {@code bumpType}, and flips the parent's {@code currentVersionId}.</p>
 */
@Entity
@Table(name = "assistant_version")
@Getter
@Setter
@NoArgsConstructor
public class AssistantVersion {

    public enum Status { DRAFT, PUBLISHED }

    public enum BumpType { PATCH, MINOR, MAJOR }

    /** Raise-only override over {@code projects.auto_hitl_on_destructive}. */
    public enum HitlOverrideMode { INHERIT, STRICT }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "assistant_id", nullable = false, updatable = false)
    private UUID assistantId;

    /** semver string ("1.0", "1.1", "2.0"). Null on an unpublished Draft. */
    @Column(name = "version_number", length = 20)
    private String versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "bump_type", length = 10)
    private BumpType bumpType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.DRAFT;

    /** The Published version this Draft was forked from (stale-draft gate). */
    @Column(name = "parent_version_id")
    private UUID parentVersionId;

    @Column(name = "draft_owner_id")
    private UUID draftOwnerId;

    // --- Brain (§4.2) ---

    @Column(name = "agent_profile_id")
    private UUID agentProfileId;

    /**
     * Pinned published {@code agent_profile_versions} row stamped at publish
     * time (assistant-entity.md §4.2: "Required Published version at publish
     * time"). Immutable once PUBLISHED.
     */
    @Column(name = "agent_profile_version_id")
    private UUID agentProfileVersionId;

    @Column(name = "addendum", columnDefinition = "text")
    private String addendum;

    // --- Behavior (§4.3) ---

    @Column(name = "greeting_message", nullable = false, columnDefinition = "text")
    private String greetingMessage = "";

    @Column(name = "max_idle_minutes", nullable = false)
    private int maxIdleMinutes = 60;

    /** Hard cap on total session duration in hours. Null = no cap. */
    @Column(name = "max_session_age_hours")
    private Integer maxSessionAgeHours;

    // --- Knowledge (§4.4) — additive list of KB ids ---

    @Convert(converter = JsonListConverter.class)
    @Column(name = "kb_bindings", nullable = false, columnDefinition = "text")
    private List<String> kbBindings = new ArrayList<>();

    // --- Tools (§4.5) — subtractive list of disabled tool codes + HITL override ---

    @Convert(converter = JsonListConverter.class)
    @Column(name = "disabled_tools", nullable = false, columnDefinition = "text")
    private List<String> disabledTools = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "hitl_override_mode", nullable = false, length = 20)
    private HitlOverrideMode hitlOverrideMode = HitlOverrideMode.INHERIT;

    // --- Reach (§4.6) — non-empty set of channels ---

    @Convert(converter = JsonListConverter.class)
    @Column(name = "usable_via", nullable = false, columnDefinition = "text")
    private List<String> usableVia = new ArrayList<>(List.of("WEB_UI"));

    // --- Attachments (§4.8) — override-only / tighten-only; null = inherit ---

    @Column(name = "attachments_enabled", nullable = false)
    private boolean attachmentsEnabled = true;

    @Column(name = "attachment_retention_ttl")
    private Integer attachmentRetentionTtl;

    @Column(name = "attachment_max_file_size")
    private Integer attachmentMaxFileSize;

    /**
     * Subtractive override allowlist as raw JSON text. Kept as a String (not a
     * converted List) so {@code NULL = inherit} stays distinct from {@code [] =
     * allow nothing} — the {@link JsonListConverter} collapses null to "[]".
     * Parsed by the attachment clamp chain when #103 / #105 land.
     */
    @Column(name = "attachment_type_allowlist", columnDefinition = "text")
    private String attachmentTypeAllowlist;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by")
    private UUID publishedBy;

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
