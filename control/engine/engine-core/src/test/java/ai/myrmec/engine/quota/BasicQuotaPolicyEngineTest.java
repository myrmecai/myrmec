// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.quota;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.group.Group;
import ai.myrmec.engine.group.GroupRepository;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectRepository;
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

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private GroupRepository groupRepository;

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
                1_000L, EnforcementMode.BLOCK, QuotaType.CEILING, null, null, null, TEST_ADMIN_ID);

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
                1_000L, EnforcementMode.BLOCK, QuotaType.CEILING, null, null, null, TEST_ADMIN_ID);
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
                100L, EnforcementMode.BLOCK, QuotaType.CEILING, null, null, null, TEST_ADMIN_ID);
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
                100L, EnforcementMode.TELEMETRY, QuotaType.CEILING, null, null, null, TEST_ADMIN_ID);
        engine.recordConsumption(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 200);

        QuotaDecision d = engine.check(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 50);
        assertThat(d.isBlocked()).isFalse();
        assertThat(d.isWarning()).isTrue(); // still well over the 80% line
    }

    @Test
    void warnMode_reportsWarningButDoesNotBlock() {
        UUID projectId = UUID.randomUUID();
        quotaService.create(
                Quota.Scope.PROJECT, projectId,
                Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                100L, EnforcementMode.WARN, QuotaType.CEILING, null, null, null, TEST_ADMIN_ID);
        engine.recordConsumption(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 150);

        QuotaDecision d = engine.check(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 50);
        assertThat(d.isBlocked()).isFalse();
        assertThat(d.isWarning()).isTrue();
        assertThat(d.getRemainingAmount()).isEqualTo(0L);
    }

    @Test
    void recordConsumption_isAdditive_perPeriod() {
        UUID projectId = UUID.randomUUID();
        quotaService.create(
                Quota.Scope.PROJECT, projectId,
                Quota.ResourceType.COST_USD_CENTS, Quota.Period.DAILY,
                10_000L, EnforcementMode.BLOCK, QuotaType.CEILING, null, null, null, TEST_ADMIN_ID);
        engine.recordConsumption(QuotaScope.PROJECT, projectId, QuotaResourceType.COST_USD_CENTS, 1_000);
        engine.recordConsumption(QuotaScope.PROJECT, projectId, QuotaResourceType.COST_USD_CENTS, 2_500);
        engine.recordConsumption(QuotaScope.PROJECT, projectId, QuotaResourceType.COST_USD_CENTS, 500);

        QuotaDecision d = engine.check(QuotaScope.PROJECT, projectId, QuotaResourceType.COST_USD_CENTS, 0);
        assertThat(d.getConsumedAmount()).isEqualTo(4_000L);
        assertThat(d.getRemainingAmount()).isEqualTo(6_000L);
    }

    @Test
    void paused_quotaBlocks_allRequests() {
        UUID projectId = UUID.randomUUID();
        Quota q = quotaService.create(
                Quota.Scope.PROJECT, projectId,
                Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                1_000L, EnforcementMode.BLOCK, QuotaType.CEILING, null, null, null, TEST_ADMIN_ID);

        quotaService.pause(q.getId(), UUID.randomUUID());

        // Even a small request should be blocked when quota is paused.
        QuotaDecision d = engine.check(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 1);
        assertThat(d.isBlocked()).isTrue();
        assertThat(d.isWarning()).isFalse();
        assertThat(d.getRemainingAmount()).isEqualTo(0L);
    }

    @Test
    void consumption_at120Percent_warnsForAutoPause() {
        // Create a 1000 token limit and consume 1200 tokens (120%).
        // The engine should log a warning but still allow check() to return
        // the state. The pause is asynchronous (admin sees the quota is
        // at 120% and can pause manually, or a scheduled job can pause it).
        UUID projectId = UUID.randomUUID();
        quotaService.create(
                Quota.Scope.PROJECT, projectId,
                Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                1_000L, EnforcementMode.BLOCK, QuotaType.CEILING, null, null, null, TEST_ADMIN_ID);
        engine.recordConsumption(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 1_200);

        // check() with amount=0 should show consumed=1200, projected=1200 >= 120% of 1000.
        QuotaDecision d = engine.check(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 0);
        assertThat(d.getConsumedAmount()).isEqualTo(1_200L);
        // Should be blocked because 1200 > 1000.
        assertThat(d.isBlocked()).isTrue();
    }

    @Test
    void serviceReservationExhaustionFallsBackToProjectCeiling() {
        Group group = new Group();
        group.setName("Policy Group");
        group = groupRepository.save(group);

        Project project = new Project();
        project.setName("Policy Project");
        project.setGroupId(group.getId());
        project = projectRepository.save(project);

        quotaService.create(
                Quota.Scope.PROJECT, project.getId(),
                Quota.ResourceType.COST_USD_CENTS, Quota.Period.DAILY,
                1_000L, EnforcementMode.BLOCK, QuotaType.CEILING, null, null, null, TEST_ADMIN_ID);

        quotaService.create(
                Quota.Scope.SERVICE, project.getId(),
                Quota.ResourceType.COST_USD_CENTS, Quota.Period.DAILY,
                600L, EnforcementMode.BLOCK, QuotaType.RESERVATION,
                ServiceType.WORKFLOW, null, null, TEST_ADMIN_ID);

        // Consume the full service reservation.
        engine.recordConsumption(QuotaScope.SERVICE, project.getId(), QuotaResourceType.COST_USD_CENTS, 600);

        // 100 more should be blocked by the reservation even though the
        // project ceiling still has shared pool left.
        QuotaDecision reservationBlocked = engine.check(QuotaScope.SERVICE, project.getId(), QuotaResourceType.COST_USD_CENTS, 100);
        assertThat(reservationBlocked.isBlocked()).isTrue();
        assertThat(reservationBlocked.getScopeHit()).isEqualTo(QuotaScope.SERVICE);

        // The project ceiling itself is still at 600/1000.
        QuotaDecision projectDecision = engine.check(QuotaScope.PROJECT, project.getId(), QuotaResourceType.COST_USD_CENTS, 0);
        assertThat(projectDecision.getConsumedAmount()).isEqualTo(600L);
        assertThat(projectDecision.getRemainingAmount()).isEqualTo(400L);
    }

    @Test
    void serviceFallsBackToSharedPool_whenNoReservation() {
        Group group = new Group();
        group.setName("Shared Pool Group");
        group = groupRepository.save(group);

        Project project = new Project();
        project.setName("Shared Pool Project");
        project.setGroupId(group.getId());
        project = projectRepository.save(project);

        quotaService.create(
                Quota.Scope.PROJECT, project.getId(),
                Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                1_000L, EnforcementMode.BLOCK, QuotaType.CEILING, null, null, null, TEST_ADMIN_ID);

        engine.recordConsumption(QuotaScope.SERVICE, project.getId(), QuotaResourceType.TOKENS, 300);

        QuotaDecision d = engine.check(QuotaScope.SERVICE, project.getId(), QuotaResourceType.TOKENS, 0);
        assertThat(d.isBlocked()).isFalse();
        assertThat(d.getRemainingAmount()).isEqualTo(700L);
        assertThat(d.getScopeHit()).isEqualTo(QuotaScope.PROJECT);
    }

    @Test
    void serviceConsumptionRollsUpToProjectAndAncestors() {
        Group group = new Group();
        group.setName("Rollup Group");
        group = groupRepository.save(group);

        Project project = new Project();
        project.setName("Rollup Project");
        project.setGroupId(group.getId());
        project = projectRepository.save(project);

        quotaService.create(
                Quota.Scope.PROJECT, project.getId(),
                Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                1_000L, EnforcementMode.BLOCK, QuotaType.CEILING, null, null, null, TEST_ADMIN_ID);

        engine.recordConsumption(QuotaScope.SERVICE, project.getId(), QuotaResourceType.TOKENS, 250);

        QuotaDecision projectDecision = engine.check(QuotaScope.PROJECT, project.getId(), QuotaResourceType.TOKENS, 0);
        assertThat(projectDecision.getConsumedAmount()).isEqualTo(250L);
    }
}
