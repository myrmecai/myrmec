// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.quota;

import ai.myrmec.engine.assistant.Assistant;
import ai.myrmec.engine.assistant.AssistantRepository;
import ai.myrmec.engine.group.Group;
import ai.myrmec.engine.group.GroupRepository;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectRepository;
import ai.myrmec.engine.quota.dto.BudgetTreeNode;
import ai.myrmec.engine.quota.dto.EffectiveQuota;
import ai.myrmec.engine.quota.dto.SharedPool;
import ai.myrmec.engine.workflow.Workflow;
import ai.myrmec.engine.workflow.WorkflowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only budget computation service.
 *
 * <p>Calculates effective quotas, shared pools, and the dashboard tree
 * from the {@code quotas} table. The service is intentionally simple in
 * Community: no org table, no recursive nesting beyond group → project →
 * service. Parent scope is resolved by walking the project/group table
 * and falling back to the nearest matching ceiling.</p>
 */
@Service
@RequiredArgsConstructor
public class BudgetService {

    private final QuotaRepository quotaRepository;
    private final QuotaConsumptionRepository consumptionRepository;
    private final ProjectRepository projectRepository;
    private final GroupRepository groupRepository;
    private final WorkflowRepository workflowRepository;
    private final AssistantRepository assistantRepository;

    /** Effective quota for a specific scope, falling back to parent ceilings. */
    @Transactional(readOnly = true)
    public EffectiveQuota effectiveQuota(Quota.Scope scope, UUID scopeId,
                                          Quota.ResourceType resource, Quota.Period period,
                                          Authentication auth) {
        Long ownLimit = null;
        Long inheritedLimit = null;
        Quota ownRow = findOwnRow(scope, scopeId, resource, period);
        if (ownRow != null) {
            ownLimit = ownRow.getLimitAmount();
        }
        if (ownRow != null && ownRow.getQuotaType() == QuotaType.RESERVATION) {
            // Reservation's own limit is its effective limit; still report inherited ceiling.
            inheritedLimit = resolveParentCeiling(scope, scopeId, resource, period);
        } else {
            inheritedLimit = resolveParentCeiling(scope, scopeId, resource, period);
        }
        Long effectiveLimit = ownLimit != null ? ownLimit : inheritedLimit;
        long consumed = computeConsumed(ownRow, resource, period);
        Long remaining = effectiveLimit != null ? Math.max(0, effectiveLimit - consumed) : null;
        boolean exceeded = effectiveLimit != null && consumed > effectiveLimit;
        boolean atRisk = effectiveLimit != null && !exceeded && consumed >= (long) (effectiveLimit * 0.8);
        return EffectiveQuota.builder()
                .id(ownRow != null ? ownRow.getId() : null)
                .scopeType(scope.name())
                .scopeId(scopeId)
                .name(scopeName(scope, scopeId))
                .resourceType(resource.name())
                .period(period.name())
                .ownLimit(ownLimit)
                .inheritedLimit(inheritedLimit)
                .effectiveLimit(effectiveLimit)
                .quotaType(ownRow != null ? ownRow.getQuotaType().name() : null)
                .enforcementMode(ownRow != null ? ownRow.getEnforcementMode().name() : null)
                .serviceType(ownRow != null && ownRow.getServiceType() != null
                        ? ownRow.getServiceType().name() : null)
                .consumed(consumed)
                .remaining(remaining)
                .atRisk(atRisk)
                .exceeded(exceeded)
                .paused(ownRow != null && ownRow.getPausedAt() != null)
                .build();
    }

    /** Shared pool remaining for services without an explicit reservation. */
    @Transactional(readOnly = true)
    public SharedPool sharedPool(UUID projectId, Quota.ResourceType resource, Quota.Period period) {
        long totalLimit = 0;
        long reservedAmount = 0;
        long consumedInShared = 0;

        // 1. Resolve the effective ceiling: PROJECT → GROUP → ORG.
        List<Quota> projectQuotas = quotaRepository.findByScopeTypeAndScopeIdAndResourceType(
                Quota.Scope.PROJECT, projectId, resource);
        Quota projectCeiling = projectQuotas.stream()
                .filter(q -> q.getQuotaType() == QuotaType.CEILING && q.getPeriod() == period)
                .findFirst()
                .orElse(null);
        if (projectCeiling != null) {
            totalLimit = projectCeiling.getLimitAmount();
        } else {
            // Fallback: GROUP ceiling, then ORG ceiling.
            Long inherited = groupThenOrgCeiling(projectId, resource, period);
            if (inherited == null) {
                return SharedPool.builder()
                        .totalLimit(0)
                        .reservedAmount(0)
                        .sharedAmount(0)
                        .consumedInShared(0)
                        .build();
            }
            totalLimit = inherited;
        }

        // 2. Sum SERVICE-scope reservations under this project (using
        //    the denormalised project_id column).
        List<Quota> serviceQuotas = quotaRepository.findByScopeTypeAndProjectIdAndResourceType(
                Quota.Scope.SERVICE, projectId, resource);
        for (Quota q : serviceQuotas) {
            if (q.getPeriod() != period) {
                continue;
            }
            if (q.getQuotaType() == QuotaType.RESERVATION) {
                reservedAmount += q.getLimitAmount();
            } else if (q.getQuotaType() == QuotaType.CEILING) {
                consumedInShared += computeConsumed(q, resource, period);
            }
        }

        return SharedPool.builder()
                .totalLimit(totalLimit)
                .reservedAmount(reservedAmount)
                .sharedAmount(Math.max(0, totalLimit - reservedAmount))
                .consumedInShared(consumedInShared)
                .build();
    }

    /** Service-level budgets under a project, including their effective limits. */
    @Transactional(readOnly = true)
    public List<EffectiveQuota> serviceBudgets(UUID projectId, Quota.ResourceType resource, Quota.Period period) {
        List<EffectiveQuota> result = new ArrayList<>();
        List<Quota> serviceQuotas = quotaRepository.findByScopeTypeAndProjectIdAndResourceType(
                Quota.Scope.SERVICE, projectId, resource);
        for (Quota q : serviceQuotas) {
            if (q.getPeriod() == period) {
                result.add(effectiveQuota(Quota.Scope.SERVICE, q.getScopeId(), resource, period, null));
            }
        }
        return result;
    }

    /** Dashboard tree filtered to scopes the caller can view. */
    @Transactional(readOnly = true)
    public List<BudgetTreeNode> buildDashboardTree(Quota.Period period,
                                                    Quota.ResourceType resource,
                                                    Authentication auth) {
        // Community: flat org/group/project tree. Start with org rows as roots.
        List<BudgetTreeNode> roots = new ArrayList<>();
        List<Quota> orgRows = quotaRepository.findByScopeTypeAndResourceTypeAndPeriod(
                Quota.Scope.ORG, resource, period);
        for (Quota orgRow : orgRows) {
            BudgetTreeNode orgNode = toTreeNode(orgRow, resource, period, auth);
            if (orgNode != null) {
                roots.add(orgNode);
            }
        }
        if (roots.isEmpty()) {
            // No org budget: build a single synthetic org node holding groups/projects.
            roots.add(syntheticOrgNode(period, resource, auth));
        }
        return roots;
    }

    private BudgetTreeNode toTreeNode(Quota quota, Quota.ResourceType resource,
                                      Quota.Period period, Authentication auth) {
        EffectiveQuota eq = effectiveQuota(quota.getScopeType(), quota.getScopeId(), resource, period, auth);
        if (eq == null) {
            return null;
        }
        return BudgetTreeNode.builder()
                .id(quota.getId())
                .scopeType(eq.getScopeType())
                .scopeId(eq.getScopeId())
                .name(eq.getName())
                .resourceType(eq.getResourceType())
                .period(eq.getPeriod())
                .effectiveLimit(eq.getEffectiveLimit())
                .consumed(eq.getConsumed())
                .quotaType(eq.getQuotaType())
                .enforcementMode(eq.getEnforcementMode())
                .paused(eq.isPaused())
                .children(childrenFor(quota.getScopeType(), quota.getScopeId(), resource, period, auth))
                .build();
    }

    private List<BudgetTreeNode> childrenFor(Quota.Scope scope, UUID scopeId,
                                             Quota.ResourceType resource, Quota.Period period,
                                             Authentication auth) {
        return switch (scope) {
            case ORG -> groupsFor(resource, period, auth).stream()
                    .map(g -> groupNode(g.getId(), resource, period, auth))
                    .toList();
            case GROUP -> projectsForGroup(scopeId, resource, period, auth).stream()
                    .map(p -> projectNode(p.getId(), resource, period, auth))
                    .toList();
            case PROJECT -> serviceNodes(scopeId, resource, period, auth);
            default -> List.of();
        };
    }

    private BudgetTreeNode groupNode(UUID groupId, Quota.ResourceType resource,
                                     Quota.Period period, Authentication auth) {
        Quota groupRow = findOwnRow(Quota.Scope.GROUP, groupId, resource, period);
        if (groupRow == null) {
            return syntheticGroupNode(groupId, resource, period, auth);
        }
        return toTreeNode(groupRow, resource, period, auth);
    }

    private BudgetTreeNode projectNode(UUID projectId, Quota.ResourceType resource,
                                       Quota.Period period, Authentication auth) {
        Quota projectRow = findOwnRow(Quota.Scope.PROJECT, projectId, resource, period);
        if (projectRow == null) {
            return syntheticProjectNode(projectId, resource, period, auth);
        }
        return toTreeNode(projectRow, resource, period, auth);
    }

    private List<BudgetTreeNode> serviceNodes(UUID projectId, Quota.ResourceType resource,
                                              Quota.Period period, Authentication auth) {
        List<Quota> serviceQuotas = quotaRepository.findByScopeTypeAndProjectIdAndResourceType(
                Quota.Scope.SERVICE, projectId, resource);
        return serviceQuotas.stream()
                .filter(q -> q.getPeriod() == period)
                .map(q -> toTreeNode(q, resource, period, auth))
                .toList();
    }

    private List<Group> groupsFor(Quota.ResourceType resource, Quota.Period period, Authentication auth) {
        // View all groups for now; authorization is checked at controller layer.
        return groupRepository.findAll();
    }

    private List<Project> projectsForGroup(UUID groupId, Quota.ResourceType resource,
                                           Quota.Period period, Authentication auth) {
        return projectRepository.findByGroupId(groupId);
    }

    private BudgetTreeNode syntheticOrgNode(Quota.Period period, Quota.ResourceType resource,
                                            Authentication auth) {
        return BudgetTreeNode.builder()
                .id(null)
                .scopeType(Quota.Scope.ORG.name())
                .scopeId(null)
                .name("Organization")
                .resourceType(resource.name())
                .period(period.name())
                .effectiveLimit(resolveOrgCeiling(resource, period) != null
                        ? resolveOrgCeiling(resource, period).getLimitAmount() : null)
                .consumed(0L)
                .quotaType(null)
                .enforcementMode(null)
                .paused(false)
                .children(childrenFor(Quota.Scope.ORG, null, resource, period, auth))
                .build();
    }

    private BudgetTreeNode syntheticGroupNode(UUID groupId, Quota.ResourceType resource,
                                              Quota.Period period, Authentication auth) {
        return BudgetTreeNode.builder()
                .id(null)
                .scopeType(Quota.Scope.GROUP.name())
                .scopeId(groupId)
                .name(scopeName(Quota.Scope.GROUP, groupId))
                .resourceType(resource.name())
                .period(period.name())
                .effectiveLimit(effectiveQuota(Quota.Scope.GROUP, groupId, resource, period, auth).getEffectiveLimit())
                .consumed(0L)
                .quotaType(null)
                .enforcementMode(null)
                .paused(false)
                .children(childrenFor(Quota.Scope.GROUP, groupId, resource, period, auth))
                .build();
    }

    private BudgetTreeNode syntheticProjectNode(UUID projectId, Quota.ResourceType resource,
                                                Quota.Period period, Authentication auth) {
        return BudgetTreeNode.builder()
                .id(null)
                .scopeType(Quota.Scope.PROJECT.name())
                .scopeId(projectId)
                .name(scopeName(Quota.Scope.PROJECT, projectId))
                .resourceType(resource.name())
                .period(period.name())
                .effectiveLimit(effectiveQuota(Quota.Scope.PROJECT, projectId, resource, period, auth).getEffectiveLimit())
                .consumed(0L)
                .quotaType(null)
                .enforcementMode(null)
                .paused(false)
                .children(childrenFor(Quota.Scope.PROJECT, projectId, resource, period, auth))
                .build();
    }

    private Quota findOwnRow(Quota.Scope scope, UUID scopeId, Quota.ResourceType resource,
                             Quota.Period period) {
        if (scope == Quota.Scope.ORG) {
            return quotaRepository.findByScopeTypeAndResourceTypeAndPeriod(scope, resource, period).stream()
                    .min(Comparator.comparingLong(Quota::getLimitAmount))
                    .orElse(null);
        }
        return quotaRepository.findByScopeTypeAndScopeIdAndResourceType(scope, scopeId, resource).stream()
                .filter(q -> q.getPeriod() == period)
                .min(Comparator.comparingLong(Quota::getLimitAmount))
                .orElse(null);
    }

    /** Returns the smallest parent CEILING limit, or null if none. */
    private Long resolveParentCeiling(Quota.Scope scope, UUID scopeId,
                                      Quota.ResourceType resource, Quota.Period period) {
        return switch (scope) {
            case ORG -> null;
            case GROUP -> orgCeiling(resource, period);
            case PROJECT -> groupThenOrgCeiling(scopeId, resource, period);
            case SERVICE -> {
                // For SERVICE scope, scopeId is the instance ID.
                // Resolve the project from the instance, then walk
                // PROJECT → GROUP → ORG.
                UUID projectId = resolveProjectFromInstance(scopeId);
                if (projectId == null) {
                    // Fallback: scopeId might be a project ID (legacy).
                    projectId = scopeId;
                }
                yield projectThenGroupThenOrgCeiling(projectId, resource, period);
            }
        };
    }

    private Quota resolveOrgCeiling(Quota.ResourceType resource, Quota.Period period) {
        return quotaRepository.findByScopeTypeAndResourceTypeAndPeriod(Quota.Scope.ORG, resource, period)
                .stream()
                .filter(q -> q.getQuotaType() == QuotaType.CEILING)
                .min(Comparator.comparingLong(Quota::getLimitAmount))
                .orElse(null);
    }

    private Long orgCeiling(Quota.ResourceType resource, Quota.Period period) {
        Quota q = resolveOrgCeiling(resource, period);
        return q != null ? q.getLimitAmount() : null;
    }

    private Long groupThenOrgCeiling(UUID projectId, Quota.ResourceType resource, Quota.Period period) {
        Optional<Project> project = projectRepository.findById(projectId);
        if (project.isPresent() && project.get().getGroupId() != null) {
            Quota group = quotaRepository.findByScopeTypeAndScopeIdAndResourceType(
                            Quota.Scope.GROUP, project.get().getGroupId(), resource)
                    .stream()
                    .filter(q -> q.getQuotaType() == QuotaType.CEILING && q.getPeriod() == period)
                    .min(Comparator.comparingLong(Quota::getLimitAmount))
                    .orElse(null);
            if (group != null) {
                return group.getLimitAmount();
            }
        }
        return orgCeiling(resource, period);
    }

    private Long projectThenGroupThenOrgCeiling(UUID projectId, Quota.ResourceType resource, Quota.Period period) {
        Quota project = quotaRepository.findByScopeTypeAndScopeIdAndResourceType(
                        Quota.Scope.PROJECT, projectId, resource)
                .stream()
                .filter(q -> q.getQuotaType() == QuotaType.CEILING && q.getPeriod() == period)
                .min(Comparator.comparingLong(Quota::getLimitAmount))
                .orElse(null);
        if (project != null) {
            return project.getLimitAmount();
        }
        return groupThenOrgCeiling(projectId, resource, period);
    }

    private long computeConsumed(Quota quota, Quota.ResourceType resource, Quota.Period period) {
        if (quota == null) {
            return 0L;
        }
        Instant periodStart = BasicQuotaPolicyEngine.periodStart(period, Instant.now());
        return consumptionRepository.findByQuotaIdAndPeriodStart(quota.getId(), periodStart)
                .map(QuotaConsumption::getAmountUsed)
                .orElse(0L);
    }

    private String scopeName(Quota.Scope scope, UUID scopeId) {
        return switch (scope) {
            case ORG -> "Organization";
            case GROUP -> groupRepository.findById(scopeId).map(Group::getName).orElse("Group " + scopeId);
            case PROJECT -> projectRepository.findById(scopeId).map(Project::getName).orElse("Project " + scopeId);
            case SERVICE -> {
                // Try workflow, then assistant.
                Optional<String> wfName = workflowRepository.findById(scopeId)
                        .map(Workflow::getName);
                if (wfName.isPresent()) {
                    yield wfName.get();
                }
                Optional<String> asstName = assistantRepository.findById(scopeId)
                        .map(Assistant::getName);
                if (asstName.isPresent()) {
                    yield asstName.get();
                }
                yield "Service " + scopeId;
            }
        };
    }

    /**
     * Resolve the project ID from a service-instance UUID by looking up
     * the workflow / assistant table.  Falls back to checking if the
     * scopeId is itself a project ID (legacy compatibility).
     */
    private UUID resolveProjectFromInstance(UUID serviceInstanceId) {
        Optional<Workflow> workflow = workflowRepository.findById(serviceInstanceId);
        if (workflow.isPresent()) {
            return workflow.get().getProject().getId();
        }
        Optional<Assistant> assistant = assistantRepository.findById(serviceInstanceId);
        if (assistant.isPresent()) {
            return assistant.get().getProjectId();
        }
        // Fallback: scopeId might be a project ID (legacy / test data).
        if (projectRepository.existsById(serviceInstanceId)) {
            return serviceInstanceId;
        }
        return null;
    }

}
