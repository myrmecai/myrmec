// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.quota;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.spi.quota.QuotaDecision;
import ai.myrmec.engine.spi.quota.QuotaResourceType;
import ai.myrmec.engine.spi.quota.QuotaScope;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #40 per-execution cost ceiling + #44 120% kill switch (quota safety-net carve-outs).
 *
 * <p>#40: a single request may never exceed a quota's per-run cap
 * ({@code maxExecutionAmount}), regardless of how much pool budget remains.</p>
 *
 * <p>#44: a BLOCK quota whose consumption reaches 120% of its limit is
 * auto-paused; an admin must explicitly resume. The pause happens even though
 * the evaluation runs in a read-only transaction (delegated to a
 * {@code REQUIRES_NEW} service).</p>
 */
@Tag("SG3")
@Tag("SG5")
class PerExecutionCapAndKillSwitchTest extends IntegrationTestBase {

    @Autowired
    private BasicQuotaPolicyEngine engine;

    @Autowired
    private QuotaService quotaService;

    @Autowired
    private QuotaRepository quotaRepository;

    // ── #40 — per-execution cost ceiling ─────────────────────────────

    @Test
    void perExecutionCapBlocksRunawayRequestDespiteRemainingPool() {
        UUID projectId = UUID.randomUUID();

        // Big pool ceiling (100k), but a per-execution cap of 50 tokens.
        quotaService.create(
                Quota.Scope.PROJECT, projectId,
                Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                100_000L, EnforcementMode.BLOCK, QuotaType.CEILING,
                null, 50L, null, TEST_ADMIN_ID);

        // Pool barely used — but a single 51-token run exceeds the 50 cap.
        QuotaDecision overCap = engine.check(QuotaScope.PROJECT, projectId,
                QuotaResourceType.TOKENS, 51);
        assertThat(overCap.isBlocked())
                .as("a single run exceeding the per-execution cap must be blocked even with budget left")
                .isTrue();

        // A 50-token run is exactly at the cap → allowed.
        QuotaDecision atCap = engine.check(QuotaScope.PROJECT, projectId,
                QuotaResourceType.TOKENS, 50);
        assertThat(atCap.isBlocked())
                .as("a run at exactly the per-execution cap must be allowed")
                .isFalse();
    }

    @Test
    void perExecutionCapIgnoredInTelemetryMode() {
        UUID projectId = UUID.randomUUID();

        // TELEMETRY mode never blocks, even over the per-execution cap.
        quotaService.create(
                Quota.Scope.PROJECT, projectId,
                Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                100_000L, EnforcementMode.TELEMETRY, QuotaType.CEILING,
                null, 50L, null, TEST_ADMIN_ID);

        QuotaDecision d = engine.check(QuotaScope.PROJECT, projectId,
                QuotaResourceType.TOKENS, 51);
        assertThat(d.isBlocked())
                .as("TELEMETRY mode must not block even over the per-execution cap")
                .isFalse();
    }

    @Test
    void noPerExecutionCapMeansNoCap() {
        UUID projectId = UUID.randomUUID();

        // No maxExecutionAmount (null) → no per-run cap.
        quotaService.create(
                Quota.Scope.PROJECT, projectId,
                Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                100_000L, EnforcementMode.BLOCK, QuotaType.CEILING,
                null, null, null, TEST_ADMIN_ID);

        QuotaDecision d = engine.check(QuotaScope.PROJECT, projectId,
                QuotaResourceType.TOKENS, 1_000);
        assertThat(d.isBlocked()).isFalse();
    }

    // ── #44 — 120% kill switch ───────────────────────────────────────

    @Test
    void blockQuotaAutoPausesAt120Percent() {
        UUID projectId = UUID.randomUUID();

        Quota q = quotaService.create(
                Quota.Scope.PROJECT, projectId,
                Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                100L, EnforcementMode.BLOCK, QuotaType.CEILING,
                null, null, null, TEST_ADMIN_ID);
        assertThat(q.getPausedAt()).isNull();

        // Drive consumption past 120% (≥120 used) then evaluate.
        engine.recordConsumption(QuotaScope.PROJECT, projectId,
                QuotaResourceType.TOKENS, 120);
        engine.check(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 1);

        Quota reloaded = quotaRepository.findById(q.getId()).orElseThrow();
        assertThat(reloaded.getPausedAt())
                .as("a BLOCK quota at 120% must be auto-paused (paused_at set)")
                .isNotNull();

        // Once paused, further checks return blocked with zero remaining.
        QuotaDecision afterPause = engine.check(QuotaScope.PROJECT, projectId,
                QuotaResourceType.TOKENS, 1);
        assertThat(afterPause.isBlocked()).isTrue();
    }

    @Test
    void underThresholdIsNotAutoPaused() {
        UUID projectId = UUID.randomUUID();

        Quota q = quotaService.create(
                Quota.Scope.PROJECT, projectId,
                Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                100L, EnforcementMode.BLOCK, QuotaType.CEILING,
                null, null, null, TEST_ADMIN_ID);

        // 119 used is UNDER the 120% threshold → no auto-pause.
        engine.recordConsumption(QuotaScope.PROJECT, projectId,
                QuotaResourceType.TOKENS, 119);
        engine.check(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 0);

        Quota reloaded = quotaRepository.findById(q.getId()).orElseThrow();
        assertThat(reloaded.getPausedAt())
                .as("a BLOCK quota under 120% must NOT be auto-paused")
                .isNull();
    }

    @Test
    void adminCanResumeAnAutoPausedQuota() {
        UUID projectId = UUID.randomUUID();

        Quota q = quotaService.create(
                Quota.Scope.PROJECT, projectId,
                Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                100L, EnforcementMode.BLOCK, QuotaType.CEILING,
                null, null, null, TEST_ADMIN_ID);

        engine.recordConsumption(QuotaScope.PROJECT, projectId,
                QuotaResourceType.TOKENS, 125);
        engine.check(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 1);
        assertThat(quotaRepository.findById(q.getId()).orElseThrow().getPausedAt()).isNotNull();

        // Admin resumes → pausedAt cleared.
        quotaService.resume(q.getId(), TEST_ADMIN_ID);
        Quota resumed = quotaRepository.findById(q.getId()).orElseThrow();
        assertThat(resumed.getPausedAt()).isNull();
        assertThat(resumed.getPausedBy()).isNull();
    }
}
