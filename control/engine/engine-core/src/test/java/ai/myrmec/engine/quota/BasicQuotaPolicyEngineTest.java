package ai.myrmec.engine.quota;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.spi.quota.QuotaDecision;
import ai.myrmec.engine.spi.quota.QuotaResourceType;
import ai.myrmec.engine.spi.quota.QuotaScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 8 &mdash; behaviour of {@link BasicQuotaPolicyEngine}: pre-flight
 * check, consumption recording, warning band, hard block, and the
 * pass-through when no quota row exists.
 */
class BasicQuotaPolicyEngineTest extends IntegrationTestBase {

    @Autowired
    private BasicQuotaPolicyEngine engine;

    @Autowired
    private QuotaService quotaService;

    @Test
    void noQuotaRow_returnsUnconstrained() {
        UUID projectId = UUID.randomUUID();
        QuotaDecision d = engine.check(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 100);
        assertThat(d.isBlocked()).isFalse();
        assertThat(d.isWarning()).isFalse();
        assertThat(d.getLimitAmount()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void underLimit_allowed_thenConsumptionRecorded() {
        UUID projectId = UUID.randomUUID();
        quotaService.create(
                Quota.Scope.PROJECT, projectId,
                Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                1_000L, true, null, null);

        QuotaDecision before = engine.check(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 100);
        assertThat(before.isBlocked()).isFalse();
        assertThat(before.isWarning()).isFalse();
        assertThat(before.getRemainingAmount()).isEqualTo(1_000L);

        engine.recordConsumption(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 100);

        QuotaDecision after = engine.check(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 50);
        assertThat(after.isBlocked()).isFalse();
        assertThat(after.getConsumedAmount()).isEqualTo(100L);
        assertThat(after.getRemainingAmount()).isEqualTo(900L);
    }

    @Test
    void warningBand_triggersAt80Percent() {
        UUID projectId = UUID.randomUUID();
        quotaService.create(
                Quota.Scope.PROJECT, projectId,
                Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                1_000L, true, null, null);
        engine.recordConsumption(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 750);

        // Projected = 750 + 50 = 800 == 80% of 1000.
        QuotaDecision d = engine.check(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 50);
        assertThat(d.isBlocked()).isFalse();
        assertThat(d.isWarning()).isTrue();
        assertThat(d.getRemainingAmount()).isEqualTo(250L);
    }

    @Test
    void overLimit_blocked_whenEnforced() {
        UUID projectId = UUID.randomUUID();
        quotaService.create(
                Quota.Scope.PROJECT, projectId,
                Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                100L, true, null, null);
        engine.recordConsumption(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 80);

        QuotaDecision d = engine.check(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 50);
        assertThat(d.isBlocked()).isTrue();
        assertThat(d.getConsumedAmount()).isEqualTo(80L);
        assertThat(d.getLimitAmount()).isEqualTo(100L);
        assertThat(d.getScopeHit()).isEqualTo(QuotaScope.PROJECT);
    }

    @Test
    void overLimit_notBlocked_whenEnforcementOff() {
        UUID projectId = UUID.randomUUID();
        quotaService.create(
                Quota.Scope.PROJECT, projectId,
                Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                100L, false /* enforced=false */, null, null);
        engine.recordConsumption(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 200);

        QuotaDecision d = engine.check(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 50);
        assertThat(d.isBlocked()).isFalse();
        assertThat(d.isWarning()).isTrue(); // still well over the 80% line
    }

    @Test
    void recordConsumption_isAdditive_perPeriod() {
        UUID projectId = UUID.randomUUID();
        quotaService.create(
                Quota.Scope.PROJECT, projectId,
                Quota.ResourceType.COST_USD_CENTS, Quota.Period.MONTHLY_CALENDAR,
                10_000L, true, null, null);
        engine.recordConsumption(QuotaScope.PROJECT, projectId, QuotaResourceType.COST_USD_CENTS, 1_000);
        engine.recordConsumption(QuotaScope.PROJECT, projectId, QuotaResourceType.COST_USD_CENTS, 2_500);
        engine.recordConsumption(QuotaScope.PROJECT, projectId, QuotaResourceType.COST_USD_CENTS, 500);

        QuotaDecision d = engine.check(QuotaScope.PROJECT, projectId, QuotaResourceType.COST_USD_CENTS, 0);
        assertThat(d.getConsumedAmount()).isEqualTo(4_000L);
        assertThat(d.getRemainingAmount()).isEqualTo(6_000L);
    }
}
