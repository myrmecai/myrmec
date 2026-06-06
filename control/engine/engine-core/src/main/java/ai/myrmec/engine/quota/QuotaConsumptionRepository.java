package ai.myrmec.engine.quota;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface QuotaConsumptionRepository extends JpaRepository<QuotaConsumption, UUID> {
    Optional<QuotaConsumption> findByQuotaIdAndPeriodStart(UUID quotaId, Instant periodStart);
}
