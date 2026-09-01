// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.quota;

import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface QuotaRepository extends JpaRepository<Quota, UUID> {
    List<Quota> findByScopeTypeAndScopeId(Quota.Scope scopeType, UUID scopeId);

    List<Quota> findByScopeTypeAndScopeIdAndResourceType(
            Quota.Scope scopeType, UUID scopeId, Quota.ResourceType resourceType);

    /**
     * Find all SERVICE-scope quotas under a given project.  Uses the
     * denormalised {@code project_id} column so we don't need to join
     * through the workflow / assistant tables.
     */
    List<Quota> findByScopeTypeAndProjectIdAndResourceType(
            Quota.Scope scopeType, UUID projectId, Quota.ResourceType resourceType);

    /**
     * Phase 8e &mdash; used by the parent-walk in {@code QuotaService} to
     * find ORG-scoped quotas (Community has no Org table to resolve a
     * specific id against, so we compare against every ORG row).
     */
    List<Quota> findByScopeTypeAndResourceTypeAndPeriod(
            Quota.Scope scopeType, Quota.ResourceType resourceType, Quota.Period period);

    /**
     * Sum of reservation limits carved out of a parent ceiling. When
     * {@code excludeId} is non-null, that quota row is excluded (used
     * during updates to avoid double-counting the current row).
     *
     * <p>For GROUP and PROJECT parents the query matches by
     * {@code scopeType} / {@code scopeId} of the parent.  For SERVICE
     * reservations under a PROJECT ceiling the reservations have
     * {@code scopeType=SERVICE} and their {@code scopeId} is the
     * instance UUID (not the project UUID), so a separate query
     * ({@link #sumServiceReservationLimitByProject}) is used.</p>
     */
    @Query("""
            SELECT COALESCE(SUM(q.limitAmount), 0)
            FROM Quota q
            WHERE q.scopeType = :parentScopeType
              AND q.scopeId = :parentScopeId
              AND q.resourceType = :resourceType
              AND q.period = :period
              AND q.quotaType = ai.myrmec.engine.quota.QuotaType.RESERVATION
              AND (:excludeId IS NULL OR q.id <> :excludeId)
            """)
    long sumReservationLimitByParent(@Param("parentScopeType") Quota.Scope parentScopeType,
                                     @Param("parentScopeId") UUID parentScopeId,
                                     @Param("resourceType") Quota.ResourceType resourceType,
                                     @Param("period") Quota.Period period,
                                     @Param("excludeId") UUID excludeId);

    /**
     * Sum of SERVICE-scope reservation limits under a given project.
     * Uses the denormalised {@code projectId} column to find all
     * SERVICE reservations belonging to the project, regardless of
     * which workflow / assistant instance they are attached to.
     */
    @Query("""
            SELECT COALESCE(SUM(q.limitAmount), 0)
            FROM Quota q
            WHERE q.scopeType = ai.myrmec.engine.quota.Quota.Scope.SERVICE
              AND q.projectId = :projectId
              AND q.resourceType = :resourceType
              AND q.period = :period
              AND q.quotaType = ai.myrmec.engine.quota.QuotaType.RESERVATION
              AND (:excludeId IS NULL OR q.id <> :excludeId)
            """)
    long sumServiceReservationLimitByProject(@Param("projectId") UUID projectId,
                                              @Param("resourceType") Quota.ResourceType resourceType,
                                              @Param("period") Quota.Period period,
                                              @Param("excludeId") UUID excludeId);

    /**
     * Find all RESERVATION rows at the next-finer scope under this ceiling.
     * Used to guard deletion of a CEILING row.
     */
    List<Quota> findByQuotaTypeAndResourceTypeAndPeriodAndScopeTypeIn(
            QuotaType quotaType,
            Quota.ResourceType resourceType,
            Quota.Period period,
            List<Quota.Scope> scopeTypes);
}
