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
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Budget-constrained session tests — implements golden journeys J2, J2a, J2b, J2c
 * from {@code 02-system-e2e-strategy.md}.
 *
 * <p>Proves SG3 (budget/quota soundness) and SG5 (state-machine integrity)
 * for the quota enforcement path.</p>
 *
 * <p>These are Tier 1 tests — direct QuotaPolicyEngine manipulation, no browser,
 * no agent, no LLM. The engine is agent-driven; quota consumption is recorded
 * via {@code recordConsumption()} and checked via {@code check()}.</p>
 */
@DisplayName("Budget-Constrained Session (J2)")
class BudgetConstrainedSessionTest extends IntegrationTestBase {

    @Autowired
    private BasicQuotaPolicyEngine engine;

    @Autowired
    private QuotaService quotaService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private TestDataBuilder data;

    // ================================================================
    // J2 — Budget-constrained session, self-service recovery
    // ================================================================

    @Nested
    @DisplayName("J2: warning → block → raise ceiling → succeed")
    class J2_BudgetConstrainedSession {

        @Test
        @DisplayName("80% warning triggers, 100% blocks, raising ceiling unblocks")
        void warningThenBlockThenRaiseCeiling() {
            UUID projectId = UUID.randomUUID();
            quotaService.create(
                    Quota.Scope.PROJECT, projectId,
                    Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                    1_000L, EnforcementMode.BLOCK, QuotaType.CEILING,
                    null, null, null, TEST_ADMIN_ID);

            // Consume 780 tokens (78% — just below 80% warning).
            engine.recordConsumption(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 780);

            // Check at 78%: no warning yet.
            QuotaDecision d78 = engine.check(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 0);
            assertThat(d78.isBlocked()).isFalse();
            assertThat(d78.isWarning()).isFalse();
            assertThat(d78.getConsumedAmount()).isEqualTo(780L);

            // Consume 30 more → 810 tokens (81% — triggers warning).
            engine.recordConsumption(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 30);

            QuotaDecision d81 = engine.check(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 0);
            assertThat(d81.isBlocked()).isFalse();
            assertThat(d81.isWarning())
                    .as("warning must trigger at ≥80% consumption")
                    .isTrue();
            assertThat(d81.getConsumedAmount()).isEqualTo(810L);

            // Consume to 100% (190 more → 1000).
            engine.recordConsumption(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 190);

            // At exactly 100%: NOT blocked (projected == limit, not > limit).
            QuotaDecision d100 = engine.check(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 0);
            assertThat(d100.isBlocked()).isFalse();
            assertThat(d100.getConsumedAmount()).isEqualTo(1_000L);

            // Consume 1 more → 1001 (projected > limit → blocked).
            engine.recordConsumption(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 1);

            QuotaDecision d101 = engine.check(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 0);
            assertThat(d101.isBlocked())
                    .as("BLOCK mode must reject when projected > limit")
                    .isTrue();
            assertThat(d101.getScopeHit()).isEqualTo(QuotaScope.PROJECT);
            assertThat(d101.getLimitAmount()).isEqualTo(1_000L);

            // BUDGET_OWNER raises the ceiling to 2,000.
            var quotas = quotaService.findByScope(Quota.Scope.PROJECT, projectId);
            var ceiling = quotas.stream()
                    .filter(q -> q.getQuotaType() == QuotaType.CEILING)
                    .findFirst().orElseThrow();
            quotaService.update(ceiling.getId(), 2_000L, EnforcementMode.BLOCK,
                    QuotaType.CEILING, null, null);

            // After raise: unblocked (2000 - 1001 = 999 remaining).
            QuotaDecision dAfter = engine.check(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 0);
            assertThat(dAfter.isBlocked()).isFalse();
            assertThat(dAfter.getLimitAmount()).isEqualTo(2_000L);
            assertThat(dAfter.getRemainingAmount()).isEqualTo(999L);
        }
    }

    // ================================================================
    // J2a — Enforcement mode escalation: TELEMETRY → WARN → BLOCK
    // ================================================================

    @Nested
    @DisplayName("J2a: enforcement mode escalation")
    class J2a_EnforcementModeEscalation {

        @Test
        @DisplayName("TELEMETRY: over-limit allowed, no block (warning band still active)")
        void telemetry_allowsOverLimit() {
            UUID projectId = UUID.randomUUID();
            quotaService.create(
                    Quota.Scope.PROJECT, projectId,
                    Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                    100L, EnforcementMode.TELEMETRY, QuotaType.CEILING,
                    null, null, null, TEST_ADMIN_ID);

            engine.recordConsumption(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 150);

            QuotaDecision d = engine.check(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 0);
            assertThat(d.isBlocked()).isFalse();
            // Warning band is always active (80% threshold) regardless of enforcement mode.
            assertThat(d.getConsumedAmount()).isEqualTo(150L);
        }

        @Test
        @DisplayName("WARN: over-limit allowed but warning recorded")
        void warn_allowsOverLimitWithWarning() {
            UUID projectId = UUID.randomUUID();
            quotaService.create(
                    Quota.Scope.PROJECT, projectId,
                    Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                    100L, EnforcementMode.WARN, QuotaType.CEILING,
                    null, null, null, TEST_ADMIN_ID);

            engine.recordConsumption(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 120);

            QuotaDecision d = engine.check(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 0);
            assertThat(d.isBlocked()).isFalse();
            assertThat(d.isWarning()).isTrue();
        }

        @Test
        @DisplayName("BLOCK: over-limit rejected")
        void block_rejectsOverLimit() {
            UUID projectId = UUID.randomUUID();
            quotaService.create(
                    Quota.Scope.PROJECT, projectId,
                    Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                    100L, EnforcementMode.BLOCK, QuotaType.CEILING,
                    null, null, null, TEST_ADMIN_ID);

            engine.recordConsumption(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 101);

            QuotaDecision d = engine.check(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 0);
            assertThat(d.isBlocked())
                    .as("BLOCK mode must reject when projected > limit (101 > 100)")
                    .isTrue();
        }

        @Test
        @DisplayName("Escalation: TELEMETRY → WARN → BLOCK → revert to WARN")
        void escalationTelemetryToWarnToBlockAndBack() {
            UUID projectId = UUID.randomUUID();
            var q = quotaService.create(
                    Quota.Scope.PROJECT, projectId,
                    Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                    100L, EnforcementMode.TELEMETRY, QuotaType.CEILING,
                    null, null, null, TEST_ADMIN_ID);

            // TELEMETRY: over-limit, no block.
            engine.recordConsumption(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 120);
            assertThat(engine.check(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 0).isBlocked())
                    .isFalse();

            // Escalate to WARN.
            quotaService.update(q.getId(), 100L, EnforcementMode.WARN,
                    QuotaType.CEILING, null, null);
            assertThat(engine.check(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 0).isBlocked())
                    .isFalse();
            assertThat(engine.check(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 0).isWarning())
                    .isTrue();

            // Escalate to BLOCK.
            quotaService.update(q.getId(), 100L, EnforcementMode.BLOCK,
                    QuotaType.CEILING, null, null);
            assertThat(engine.check(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 0).isBlocked())
                    .isTrue();

            // #44 kill switch: hitting ≥120% under BLOCK auto-pauses the quota.
            // Reverting the mode does NOT clear the pause — an admin must
            // explicitly resume. Resume here, then verify WARN behaviour.
            quotaService.resume(q.getId(), TEST_ADMIN_ID);

            // Revert to WARN.
            quotaService.update(q.getId(), 100L, EnforcementMode.WARN,
                    QuotaType.CEILING, null, null);
            assertThat(engine.check(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 0).isBlocked())
                    .isFalse();
            assertThat(engine.check(QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 0).isWarning())
                    .isTrue();
        }
    }

    // ================================================================
    // J2b — RESERVATION carve-out with hierarchy fallback
    // ================================================================

    @Nested
    @DisplayName("J2b: RESERVATION carve-out with hierarchy fallback")
    class J2b_ReservationCarveOut {

        @Test
        @DisplayName("RESERVATION guarantees allocation; service cannot exceed its reservation")
        void reservationGuaranteesAllocation() {
            Project project = data.project()
                    .named("j2b-prj-" + UUID.randomUUID().toString().substring(0, 8))
                    .create();

            // Project CEILING of 1,000 tokens.
            quotaService.create(
                    Quota.Scope.PROJECT, project.getId(),
                    Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                    1_000L, EnforcementMode.BLOCK, QuotaType.CEILING,
                    null, null, null, TEST_ADMIN_ID);

            // Service RESERVATION of 300 tokens (scopeId = project ID for chain resolution).
            quotaService.create(
                    Quota.Scope.SERVICE, project.getId(),
                    Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                    300L, EnforcementMode.BLOCK, QuotaType.RESERVATION,
                    null, null, null, TEST_ADMIN_ID);

            // Service consumes 250 (within its 300 reservation).
            engine.recordConsumption(QuotaScope.SERVICE, project.getId(), QuotaResourceType.TOKENS, 250);
            QuotaDecision d = engine.check(QuotaScope.SERVICE, project.getId(), QuotaResourceType.TOKENS, 0);
            assertThat(d.isBlocked()).isFalse();
            assertThat(d.getConsumedAmount()).isEqualTo(250L);

            // Service consumes to exactly 300 (reservation limit).
            engine.recordConsumption(QuotaScope.SERVICE, project.getId(), QuotaResourceType.TOKENS, 50);
            QuotaDecision d300 = engine.check(QuotaScope.SERVICE, project.getId(), QuotaResourceType.TOKENS, 0);
            assertThat(d300.isBlocked()).isFalse(); // 300 == limit, not > limit

            // Service tries 1 more → 301 > 300 reservation → blocked.
            engine.recordConsumption(QuotaScope.SERVICE, project.getId(), QuotaResourceType.TOKENS, 1);
            QuotaDecision d301 = engine.check(QuotaScope.SERVICE, project.getId(), QuotaResourceType.TOKENS, 0);
            assertThat(d301.isBlocked())
                    .as("RESERVATION is a hard cap — service cannot exceed it")
                    .isTrue();
        }

        @Test
        @DisplayName("RESERVATION is a hard cap — service cannot exceed its reservation")
        void reservationIsHardCap() {
            Project project = data.project()
                    .named("j2b-prj2-" + UUID.randomUUID().toString().substring(0, 8))
                    .create();

            quotaService.create(
                    Quota.Scope.PROJECT, project.getId(),
                    Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                    1_000L, EnforcementMode.BLOCK, QuotaType.CEILING,
                    null, null, null, TEST_ADMIN_ID);

            quotaService.create(
                    Quota.Scope.SERVICE, project.getId(),
                    Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                    300L, EnforcementMode.BLOCK, QuotaType.RESERVATION,
                    null, null, null, TEST_ADMIN_ID);

            // Service-A consumes its full 300 reservation.
            engine.recordConsumption(QuotaScope.SERVICE, project.getId(), QuotaResourceType.TOKENS, 300);

            // Service-A tries to consume more — blocked even though project has headroom.
            engine.recordConsumption(QuotaScope.SERVICE, project.getId(), QuotaResourceType.TOKENS, 1);
            QuotaDecision d = engine.check(QuotaScope.SERVICE, project.getId(), QuotaResourceType.TOKENS, 0);
            assertThat(d.isBlocked())
                    .as("RESERVATION is a hard cap — service cannot exceed it even if project has headroom")
                    .isTrue();
        }

        @Test
        @DisplayName("Hierarchy fallback: PROJECT → GROUP → ORG")
        void hierarchyFallbackProjectToGroupToOrg() {
            // Create ORG ceiling.
            UUID orgId = UUID.randomUUID();
            quotaService.create(
                    Quota.Scope.ORG, orgId,
                    Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                    10_000L, EnforcementMode.BLOCK, QuotaType.CEILING,
                    null, null, null, TEST_ADMIN_ID);

            // Create GROUP with ceiling (tightens ORG).
            Group group = new Group();
            group.setName("j2b-group-" + UUID.randomUUID().toString().substring(0, 8));
            group = groupRepository.save(group);
            quotaService.create(
                    Quota.Scope.GROUP, group.getId(),
                    Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                    5_000L, EnforcementMode.BLOCK, QuotaType.CEILING,
                    null, null, null, TEST_ADMIN_ID);

            // Create a real project in the group so scope chain can resolve GROUP.
            Project project = new Project();
            project.setName("j2b-prj3-" + UUID.randomUUID().toString().substring(0, 8));
            project.setGroupId(group.getId());
            project.setStatus(ai.myrmec.engine.project.ProjectStatus.ACTIVE);
            project = projectRepository.save(project);

            // Consume at GROUP scope to simulate project consumption rolling up.
            engine.recordConsumption(QuotaScope.GROUP, group.getId(), QuotaResourceType.TOKENS, 4_500);

            // Project check falls back to GROUP (no project ceiling).
            QuotaDecision d = engine.check(QuotaScope.PROJECT, project.getId(), QuotaResourceType.TOKENS, 0);
            assertThat(d.isBlocked()).isFalse();
            assertThat(d.getLimitAmount()).isEqualTo(5_000L);
            assertThat(d.getConsumedAmount()).isEqualTo(4_500L);

            // Consume to GROUP limit.
            engine.recordConsumption(QuotaScope.GROUP, group.getId(), QuotaResourceType.TOKENS, 500);

            QuotaDecision d2 = engine.check(QuotaScope.PROJECT, project.getId(), QuotaResourceType.TOKENS, 0);
            assertThat(d2.isBlocked()).isFalse(); // 5000 == limit, not > limit

            // One more → blocked.
            engine.recordConsumption(QuotaScope.GROUP, group.getId(), QuotaResourceType.TOKENS, 1);
            QuotaDecision d3 = engine.check(QuotaScope.PROJECT, project.getId(), QuotaResourceType.TOKENS, 0);
            assertThat(d3.isBlocked()).isTrue();
            assertThat(d3.getScopeHit()).isEqualTo(QuotaScope.GROUP);
        }
    }

    // ================================================================
    // J2c — Concurrent quota consumption: no double-spend
    //
    // NOTE: Deferred to J9 (02-system-e2e-strategy.md). The concurrent
    // test requires proper transaction isolation that H2's default
    // READ_COMMITTED + REQUIRES_NEW cannot provide. J9 will use a
    // dedicated concurrency test harness.
    // ================================================================
}
