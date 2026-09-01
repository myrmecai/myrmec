// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.quota;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.group.Group;
import ai.myrmec.engine.group.GroupRepository;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectRepository;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RECON-06: Budget ceiling fallback contract.
 *
 * <p>Verifies the ORG→GROUP→PROJECT→SERVICE spend-ceiling fallback:
 * a child scope's ceiling tightens or equals the parent — never loosens.
 * When a child has no quota, the parent's ceiling applies.
 *
 * <p>Note: context-assembly token budget is a separate project setting,
 * not quota-enforced — that separation is the second part of this contract.
 */
@Tag("RECON-06")
@Tag("SG3")
@DisplayName("RECON-06: Budget Ceiling Fallback Contract")
class BudgetCeilingFallbackContractTest extends IntegrationTestBase {

    @Autowired
    private QuotaService quotaService;

    @Autowired
    private BasicQuotaPolicyEngine engine;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private TestDataBuilder data;

    @Test
    @DisplayName("PROJECT ceiling tightens GROUP ceiling (child ≤ parent)")
    void projectTightensGroup() {
        UUID groupId = Group.DEFAULT_GROUP_ID;
        quotaService.create(
                Quota.Scope.GROUP, groupId,
                Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                1000L, EnforcementMode.BLOCK, QuotaType.CEILING,
                null, null, null, null);

        // Project ceiling of 500 is tighter than GROUP 1000 — allowed
        Project project = projectRepository.save(
                ai.myrmec.engine.TestDataFactory.projectBuilder("recon06-proj").build());
        quotaService.create(
                Quota.Scope.PROJECT, project.getId(),
                Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                500L, EnforcementMode.BLOCK, QuotaType.CEILING,
                null, null, null, null);

        // Consume 600 at PROJECT scope → should block (600 > 500 project ceiling)
        var decision = engine.check(
                ai.myrmec.engine.spi.quota.QuotaScope.PROJECT, project.getId(),
                ai.myrmec.engine.spi.quota.QuotaResourceType.TOKENS, 600);
        assertThat(decision.isBlocked()).isTrue();
    }

    @Test
    @DisplayName("PROJECT ceiling looser than GROUP is rejected")
    void projectLooserThanGroupRejected() {
        UUID groupId = Group.DEFAULT_GROUP_ID;
        quotaService.create(
                Quota.Scope.GROUP, groupId,
                Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                500L, EnforcementMode.BLOCK, QuotaType.CEILING,
                null, null, null, null);

        Project project = projectRepository.save(
                ai.myrmec.engine.TestDataFactory.projectBuilder("recon06-loose").build());
        // 1000 > 500 GROUP ceiling — rejected
        assertThatThrownBy(() -> quotaService.create(
                Quota.Scope.PROJECT, project.getId(),
                Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                1000L, EnforcementMode.BLOCK, QuotaType.CEILING,
                null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds parent");
    }

    @Test
    @DisplayName("no PROJECT quota → GROUP ceiling applies (fallback)")
    void noProjectQuotaGroupApplies() {
        UUID groupId = UUID.randomUUID();
        quotaService.create(
                Quota.Scope.GROUP, groupId,
                Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                500L, EnforcementMode.BLOCK, QuotaType.CEILING,
                null, null, null, null);

        // No project quota — GROUP ceiling should apply
        var decision = engine.check(
                ai.myrmec.engine.spi.quota.QuotaScope.GROUP, groupId,
                ai.myrmec.engine.spi.quota.QuotaResourceType.TOKENS, 600);
        assertThat(decision.isBlocked()).isTrue();
    }

    @Test
    @DisplayName("no quota at any scope → unconstrained")
    void noQuotaUnconstrained() {
        UUID randomProject = UUID.randomUUID();
        var decision = engine.check(
                ai.myrmec.engine.spi.quota.QuotaScope.PROJECT, randomProject,
                ai.myrmec.engine.spi.quota.QuotaResourceType.TOKENS, 1_000_000);
        assertThat(decision.isBlocked()).isFalse();
    }
}