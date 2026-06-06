package ai.myrmec.engine.quota;

import ai.myrmec.engine._system.common.JsonMapConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 8a &mdash; quota row. A single ceiling applied to one (scope,
 * resource, period) tuple.
 *
 * <p>{@link Scope} chains org &rarr; group &rarr; project &rarr; user
 * with the child-tightens-only rule (Phase 8e): a project quota cannot
 * be more generous than its parent group quota for the same resource +
 * period. Enforcement of that rule lives in {@code QuotaService}; the
 * row itself is polymorphic on {@link #scopeId} (no FK).</p>
 *
 * <p>{@link #limitAmount} units are determined by {@link #resourceType}:
 * {@link ResourceType#TOKENS} is raw token count;
 * {@link ResourceType#COST_USD_CENTS} is USD &times; 100 to keep the
 * column as {@code bigint}.</p>
 */
@Entity
@Table(name = "quotas")
@Getter
@Setter
@NoArgsConstructor
public class Quota {

    public enum Scope { ORG, GROUP, PROJECT, USER }

    public enum ResourceType { TOKENS, COST_USD_CENTS }

    /**
     * {@link Period#LIFETIME} buckets the entire history into one row.
     * {@link Period#DAILY} resets at UTC midnight.
     * {@link Period#MONTHLY_CALENDAR} resets on the 1st UTC.
     */
    public enum Period { DAILY, MONTHLY_CALENDAR, LIFETIME }

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    private Scope scopeType;

    @Column(name = "scope_id", nullable = false)
    private UUID scopeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 20)
    private ResourceType resourceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "period", nullable = false, length = 20)
    private Period period;

    @Column(name = "limit_amount", nullable = false)
    private long limitAmount;

    /**
     * When {@code false}, consumption is recorded but enforcement is
     * skipped &mdash; used to roll a tightened limit out as a warning
     * for a period before flipping the switch.
     */
    @Column(name = "enforced", nullable = false)
    private boolean enforced = true;

    /** Free-form labels (cost-centre, customer-id). JSON. */
    @Convert(converter = JsonMapConverter.class)
    @Column(name = "tags", columnDefinition = "clob")
    private Map<String, Object> tags;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
