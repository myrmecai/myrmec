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
     * Find all RESERVATION rows at the next-finer scope under this ceiling.
     * Used to guard deletion of a CEILING row.
     */
    List<Quota> findByQuotaTypeAndResourceTypeAndPeriodAndScopeTypeIn(
            QuotaType quotaType,
            Quota.ResourceType resourceType,
            Quota.Period period,
            List<Quota.Scope> scopeTypes);
}
