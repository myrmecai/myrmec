package ai.myrmec.engine.quota;

import ai.myrmec.engine.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QuotaEntityMappingTest extends IntegrationTestBase {

    @Autowired private QuotaRepository quotaRepository;

    @Test
    void serviceQuotaWithCeilingAndWarnModePersists() {
        Quota q = new Quota();
        q.setScopeType(Quota.Scope.SERVICE);
        q.setScopeId(UUID.randomUUID());
        q.setResourceType(Quota.ResourceType.COST_USD_CENTS);
        q.setPeriod(Quota.Period.MONTHLY_CALENDAR);
        q.setLimitAmount(50_000L);
        q.setQuotaType(QuotaType.CEILING);
        q.setEnforcementMode(EnforcementMode.WARN);
        q.setServiceType(ServiceType.WORKFLOW);
        q.setMaxExecutionAmount(1_000L);

        Quota saved = quotaRepository.save(q);
        Quota loaded = quotaRepository.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getScopeType()).isEqualTo(Quota.Scope.SERVICE);
        assertThat(loaded.getQuotaType()).isEqualTo(QuotaType.CEILING);
        assertThat(loaded.getEnforcementMode()).isEqualTo(EnforcementMode.WARN);
        assertThat(loaded.getServiceType()).isEqualTo(ServiceType.WORKFLOW);
        assertThat(loaded.getMaxExecutionAmount()).isEqualTo(1_000L);
    }
}
