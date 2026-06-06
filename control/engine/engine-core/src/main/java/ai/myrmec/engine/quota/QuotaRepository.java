package ai.myrmec.engine.quota;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
