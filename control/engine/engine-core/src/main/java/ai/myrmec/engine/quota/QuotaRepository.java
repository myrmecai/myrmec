package ai.myrmec.engine.quota;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface QuotaRepository extends JpaRepository<Quota, UUID> {
    List<Quota> findByScopeTypeAndScopeId(Quota.Scope scopeType, UUID scopeId);

    List<Quota> findByScopeTypeAndScopeIdAndResourceType(
            Quota.Scope scopeType, UUID scopeId, Quota.ResourceType resourceType);
}
