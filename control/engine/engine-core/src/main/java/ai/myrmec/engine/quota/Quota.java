// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

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
 * Phase 8a/8g &mdash; budget/quota row for a single (scope, resource, period) tuple.
 *
 * <p>{@link Scope} chains org &rarr; group &rarr; project &rarr; service.
 * The child-tightens-only rule (Phase 8e) is enforced in {@link QuotaService};
 * the row itself is polymorphic on {@link #scopeId} (no FK).</p>
 *
 * <p>{@link #quotaType} distinguishes a maximum ceiling from a guaranteed
 * reservation carved out of the parent budget. {@link #enforcementMode}
 * controls whether over-budget spend is recorded, warned, or blocked.</p>
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

    public enum Scope { ORG, GROUP, PROJECT, SERVICE }

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

    /**
     * Polymorphic scope identifier.  For {@code ORG} this is {@code null}
     * (no org entity in Community).  For {@code GROUP} / {@code PROJECT}
     * it is the group / project UUID.  For {@code SERVICE} it is the
     * workflow or assistant instance UUID — <em>not</em> the project ID.
     *
     * @see #projectId
     */
    @Column(name = "scope_id")
    private UUID scopeId;

    /**
     * Denormalised project reference for {@code SERVICE}-scope rows.
     * Populated at create time so {@code BudgetService} can find all
     * service-instance budgets under a project without joining through
     * the workflow / assistant tables.  {@code null} for non-SERVICE
     * scopes.
     */
    @Column(name = "project_id")
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 20)
    private ResourceType resourceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "period", nullable = false, length = 20)
    private Period period;

    @Column(name = "limit_amount", nullable = false)
    private long limitAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "quota_type", nullable = false, length = 20)
    private QuotaType quotaType = QuotaType.CEILING;

    @Enumerated(EnumType.STRING)
    @Column(name = "enforcement_mode", nullable = false, length = 20)
    private EnforcementMode enforcementMode = EnforcementMode.BLOCK;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", length = 20)
    private ServiceType serviceType;

    @Column(name = "max_execution_amount")
    private Long maxExecutionAmount;

    /**
     * Legacy boolean enforcement flag. Kept for backward compatibility;
     * new code should prefer {@link #enforcementMode}.
     * @deprecated use {@link #enforcementMode}
     */
    @Deprecated(forRemoval = false)
    @Column(name = "enforced", nullable = false)
    private boolean enforced = true;

    @Deprecated(forRemoval = false)
    @Column(name = "max_execution_cost_cents")
    private Long maxExecutionCostCents;

    @Column(name = "paused_at")
    private Instant pausedAt;

    @Column(name = "paused_by")
    private UUID pausedBy;

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
