// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.quota;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.group.Group;
import ai.myrmec.engine.group.GroupRepository;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectRepository;
import ai.myrmec.engine.quota.dto.SharedPool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 8f &mdash; computation of effective quotas, shared pools, and
 * dashboard tree.
 */
class BudgetServiceTest extends IntegrationTestBase {

    @Autowired private QuotaService quotaService;
    @Autowired private BudgetService budgetService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private GroupRepository groupRepository;

    @Test
    void sharedPoolSubtractsReservations() {
        Group group = new Group();
        group.setName("Budget Group");
        group = groupRepository.save(group);

        Project project = new Project();
        project.setName("Budget Project");
        project.setGroupId(group.getId());
        project = projectRepository.save(project);

        Quota projectBudget = quotaService.create(
                Quota.Scope.PROJECT, project.getId(), Quota.ResourceType.COST_USD_CENTS,
                Quota.Period.MONTHLY_CALENDAR, 1_000L, EnforcementMode.BLOCK,
                QuotaType.CEILING, null, null, null, TEST_ADMIN_ID);

        quotaService.create(
                Quota.Scope.SERVICE, project.getId(), Quota.ResourceType.COST_USD_CENTS,
                Quota.Period.MONTHLY_CALENDAR, 600L, EnforcementMode.BLOCK,
                QuotaType.RESERVATION, ServiceType.WORKFLOW, null, null, TEST_ADMIN_ID);

        SharedPool pool = budgetService.sharedPool(project.getId(), Quota.ResourceType.COST_USD_CENTS, Quota.Period.MONTHLY_CALENDAR);

        assertThat(pool.getTotalLimit()).isEqualTo(1_000L);
        assertThat(pool.getReservedAmount()).isEqualTo(600L);
        assertThat(pool.getSharedAmount()).isEqualTo(400L);
    }
}
