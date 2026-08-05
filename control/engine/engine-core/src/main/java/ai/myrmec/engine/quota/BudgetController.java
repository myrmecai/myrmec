// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.quota;

import ai.myrmec.engine.quota.dto.DashboardResponse;
import ai.myrmec.engine.quota.dto.EffectiveQuotasResponse;
import ai.myrmec.engine.quota.dto.ProjectServiceBudgetsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Read-only budget dashboard and effective-quota endpoints.
 */
@RestController
@RequestMapping("/api/v1/budgets")
@RequiredArgsConstructor
public class BudgetController {

    private final BudgetService budgetService;
    private final BudgetAuthorization budgetAuthorization;

    @GetMapping("/dashboard")
    public DashboardResponse dashboard(
            @RequestParam("period") String period,
            @RequestParam("resourceType") String resourceType,
            Authentication auth) {
        Quota.Period p = Quota.Period.valueOf(period);
        Quota.ResourceType r = Quota.ResourceType.valueOf(resourceType);
        return DashboardResponse.builder()
                .tree(budgetService.buildDashboardTree(p, r, auth))
                .build();
    }

    @GetMapping("/projects/{projectId}/services")
    public ProjectServiceBudgetsResponse projectServices(
            @PathVariable("projectId") UUID projectId,
            @RequestParam("resourceType") String resourceType,
            @RequestParam("period") String period,
            Authentication auth) {
        requireView(Quota.Scope.PROJECT, projectId, auth);
        Quota.ResourceType r = Quota.ResourceType.valueOf(resourceType);
        Quota.Period p = Quota.Period.valueOf(period);
        return ProjectServiceBudgetsResponse.builder()
                .projectId(projectId)
                .resourceType(r.name())
                .period(p.name())
                .sharedPool(budgetService.sharedPool(projectId, r, p))
                .serviceBudgets(budgetService.serviceBudgets(projectId, r, p))
                .build();
    }

    @GetMapping("/projects/{projectId}/effective-quotas")
    public EffectiveQuotasResponse projectEffectiveQuotas(
            @PathVariable("projectId") UUID projectId,
            @RequestParam("resourceType") String resourceType,
            @RequestParam("period") String period,
            Authentication auth) {
        requireView(Quota.Scope.PROJECT, projectId, auth);
        Quota.ResourceType r = Quota.ResourceType.valueOf(resourceType);
        Quota.Period p = Quota.Period.valueOf(period);
        return EffectiveQuotasResponse.builder()
                .quotas(java.util.List.of(
                        budgetService.effectiveQuota(Quota.Scope.PROJECT, projectId, r, p, auth)))
                .build();
    }

    @GetMapping("/groups/{groupId}/effective-quotas")
    public EffectiveQuotasResponse groupEffectiveQuotas(
            @PathVariable("groupId") UUID groupId,
            @RequestParam("resourceType") String resourceType,
            @RequestParam("period") String period,
            Authentication auth) {
        requireView(Quota.Scope.GROUP, groupId, auth);
        Quota.ResourceType r = Quota.ResourceType.valueOf(resourceType);
        Quota.Period p = Quota.Period.valueOf(period);
        return EffectiveQuotasResponse.builder()
                .quotas(java.util.List.of(
                        budgetService.effectiveQuota(Quota.Scope.GROUP, groupId, r, p, auth)))
                .build();
    }

    private void requireView(Quota.Scope scope, UUID scopeId, Authentication auth) {
        if (!budgetAuthorization.permissionsFor(scope, scopeId, auth).canView()) {
            throw new AccessDeniedException("Cannot view budget");
        }
    }
}
