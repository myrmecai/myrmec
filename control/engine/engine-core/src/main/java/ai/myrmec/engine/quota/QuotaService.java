// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.quota;

import ai.myrmec.engine._system.common.DomainConstants.AuditAction;
import ai.myrmec.engine._system.common.ResourceType;
import ai.myrmec.engine.assistant.Assistant;
import ai.myrmec.engine.assistant.AssistantRepository;
import ai.myrmec.engine.audit.AuditEventService;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectRepository;
import ai.myrmec.engine.workflow.Workflow;
import ai.myrmec.engine.workflow.WorkflowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Budget and quota administration service. Owns the hierarchical
 * budget invariants (Phase 8e): a CEILING at level N must not be more
 * generous than its parent CEILING; a RESERVATION must fit within the
 * parent CEILING's remaining shared pool.
 *
 * <p>Hierarchy parent lookup is intentionally simple in Community:
 * we don't crawl the org/group/project relationship table here, we
 * just compare against quotas at the next-higher scope that have
 * matching (resource, period). Enterprise can layer in inherited
 * scoping later by overriding the bean.</p>
 *
 * <p>Every mutation records an audit row under action
 * {@code QUOTA_CREATED} / {@code QUOTA_UPDATED} / {@code QUOTA_DELETED}
 * so the budget UI history pane has clean data.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaService {

    private final QuotaRepository quotaRepository;
    private final AuditEventService auditEventService;
    private final ProjectRepository projectRepository;
    private final WorkflowRepository workflowRepository;
    private final AssistantRepository assistantRepository;

    /**
     * Resolve the project ID for a SERVICE-scope quota by looking up the
     * workflow / assistant instance.  Returns {@code null} for non-SERVICE
     * scopes or when the instance cannot be found.
     */
    private UUID resolveProjectIdForService(Quota.Scope scope, UUID scopeId, ServiceType serviceType) {
        if (scope != Quota.Scope.SERVICE || scopeId == null) {
            return null;
        }
        // Try the workflow table first (covers both WORKFLOW and
        // serviceType=null cases).
        Optional<Workflow> wf = workflowRepository.findById(scopeId);
        if (wf.isPresent()) {
            return wf.get().getProject().getId();
        }
        // Try the assistant table.
        Optional<Assistant> asst = assistantRepository.findById(scopeId);
        if (asst.isPresent()) {
            return asst.get().getProjectId();
        }
        // Legacy fallback: scopeId might be a project ID itself (old
        // test data or tests that didn't create a workflow/assistant
        // instance). This preserves backward compatibility.
        if (projectRepository.existsById(scopeId)) {
            return scopeId;
        }
        return null;
    }

    @Transactional(readOnly = true)
    public List<Quota> findByScope(Quota.Scope scope, UUID scopeId) {
        return quotaRepository.findByScopeTypeAndScopeId(scope, scopeId);
    }

    @Transactional(readOnly = true)
    public List<Quota> findAll() {
        return quotaRepository.findAll();
    }

    @Transactional
    public Quota create(Quota.Scope scope, UUID scopeId, Quota.ResourceType resource,
                        Quota.Period period, long limitAmount, EnforcementMode enforcementMode,
                        QuotaType quotaType, ServiceType serviceType, Long maxExecutionAmount,
                        Map<String, Object> tags, UUID createdBy) {
        UUID projectId = resolveProjectIdForService(scope, scopeId, serviceType);
        validateBudgetCreateOrUpdate(scope, scopeId, projectId, resource, period, limitAmount, quotaType);
        Quota q = new Quota();
        q.setScopeType(scope);
        q.setScopeId(scopeId);
        q.setProjectId(projectId);
        q.setResourceType(resource);
        q.setPeriod(period);
        q.setLimitAmount(limitAmount);
        q.setQuotaType(quotaType);
        q.setEnforcementMode(enforcementMode);
        q.setEnforced(enforcementMode == EnforcementMode.BLOCK);
        q.setServiceType(serviceType);
        q.setMaxExecutionAmount(maxExecutionAmount);
        q.setTags(tags);
        q.setCreatedBy(createdBy);
        Quota saved = quotaRepository.save(q);
        audit(AuditAction.CREATED, saved);
        return saved;
    }

    @Transactional
    public Quota update(UUID id, Long newLimitAmount, EnforcementMode enforcementMode,
                        QuotaType quotaType, Long maxExecutionAmount, Map<String, Object> tags) {
        Quota q = quotaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Quota not found: " + id));
        // Partial update: keep existing values for any field that is null.
        long resolvedLimit = newLimitAmount != null ? newLimitAmount : q.getLimitAmount();
        validateBudgetCreateOrUpdate(q.getScopeType(), q.getScopeId(), q.getProjectId(),
                q.getResourceType(),
                q.getPeriod(), resolvedLimit, quotaType != null ? quotaType : q.getQuotaType());
        // Only record a limit-change event when the limit actually changed.
        if (newLimitAmount != null && newLimitAmount != q.getLimitAmount()) {
            recordQuotaChange(q, newLimitAmount);
        }
        q.setLimitAmount(resolvedLimit);
        // If enforcementMode is null, keep the existing value.
        q.setEnforcementMode(enforcementMode != null ? enforcementMode : q.getEnforcementMode());
        q.setEnforced(q.getEnforcementMode() == EnforcementMode.BLOCK);
        // If quotaType is null, keep the existing value.
        q.setQuotaType(quotaType != null ? quotaType : q.getQuotaType());
        // If maxExecutionAmount is null, keep the existing value (partial update).
        q.setMaxExecutionAmount(maxExecutionAmount != null ? maxExecutionAmount : q.getMaxExecutionAmount());
        // If tags is null, keep the existing value (partial update).
        q.setTags(tags != null ? tags : q.getTags());
        Quota saved = quotaRepository.save(q);
        audit(AuditAction.UPDATED, saved);
        return saved;
    }

    @Transactional
    public void delete(UUID id) {
        Quota q = quotaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Quota not found: " + id));
        if (q.getQuotaType() == QuotaType.CEILING) {
            List<Quota> childReservations = findChildReservationsForCeiling(q);
            if (!childReservations.isEmpty()) {
                throw new IllegalArgumentException(String.format(
                        "Cannot delete CEILING at %s/%s because %d child RESERVATION(s) depend on it",
                        q.getScopeType(), q.getScopeId(), childReservations.size()));
            }
        }
        audit(AuditAction.DELETED, q);
        quotaRepository.delete(q);
    }

    private List<Quota> findChildReservationsForCeiling(Quota ceiling) {
        // For PROJECT ceilings, child reservations are SERVICE-scope rows
        // under that specific project (matched by the denormalised
        // projectId column).  For other scopes, use the global lookup.
        if (ceiling.getScopeType() == Quota.Scope.PROJECT) {
            return quotaRepository.findByScopeTypeAndProjectIdAndResourceType(
                            Quota.Scope.SERVICE, ceiling.getScopeId(), ceiling.getResourceType())
                    .stream()
                    .filter(q -> q.getQuotaType() == QuotaType.RESERVATION
                            && q.getPeriod() == ceiling.getPeriod())
                    .toList();
        }
        List<Quota.Scope> childScopes = switch (ceiling.getScopeType()) {
            case ORG -> List.of(Quota.Scope.GROUP);
            case GROUP -> List.of(Quota.Scope.PROJECT);
            case PROJECT -> List.of(Quota.Scope.SERVICE);
            case SERVICE -> List.of();
        };
        if (childScopes.isEmpty()) {
            return List.of();
        }
        return quotaRepository.findByQuotaTypeAndResourceTypeAndPeriodAndScopeTypeIn(
                QuotaType.RESERVATION, ceiling.getResourceType(), ceiling.getPeriod(), childScopes);
    }

    @Transactional
    public Quota pause(UUID id, UUID pausedBy) {
        Quota q = quotaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Quota not found: " + id));
        q.setPausedAt(Instant.now());
        q.setPausedBy(pausedBy);
        Quota saved = quotaRepository.save(q);
        audit(AuditAction.PAUSED, saved);
        return saved;
    }

    @Transactional
    public Quota resume(UUID id, UUID resumedBy) {
        Quota q = quotaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Quota not found: " + id));
        q.setPausedAt(null);
        q.setPausedBy(null);
        Quota saved = quotaRepository.save(q);
        audit(AuditAction.RESUMED, saved);
        return saved;
    }

    /**
     * Phase 8e &mdash; child-tightens-only: a quota at level N must
     * not be more generous than any matching quota at level N-1
     * (ORG &gt; GROUP &gt; PROJECT &gt; SERVICE, in that order of
     * generosity).
     *
     * <p>Walk performed:
     * <ul>
     *   <li>{@code SERVICE} &mdash; resolve via {@code projectRepository}
     *       to get {@code groupId}, then check project + group + every ORG
     *       quota that matches (resource, period).</li>
     *   <li>{@code PROJECT} &mdash; resolve via {@code projectRepository}
     *       to get {@code groupId}, then check group + every ORG
     *       quota that matches (resource, period).</li>
     *   <li>{@code GROUP} &mdash; check every ORG quota that matches
     *       (resource, period). Community has no Org table so we
     *       compare against all ORG rows.</li>
     *   <li>{@code ORG} &mdash; top of hierarchy, pass through.</li>
     * </ul>
     */
    private void validateBudgetCreateOrUpdate(Quota.Scope scope, UUID scopeId, UUID projectId,
                                              Quota.ResourceType resource,
                                              Quota.Period period, long limitAmount,
                                              QuotaType quotaType) {
        if (quotaType == QuotaType.RESERVATION) {
            validateReservationFits(scope, scopeId, projectId, resource, period, limitAmount);
            return;
        }
        switch (scope) {
            case ORG -> {
                // Top of hierarchy — no parent to check.
            }
            case SERVICE -> {
                // For SERVICE scope, resolve the project from the denormalised
                // projectId (resolved from the workflow/assistant instance).
                if (projectId == null) {
                    return;
                }
                Optional<Project> project = projectRepository.findById(projectId);
                if (project.isEmpty()) {
                    return;
                }
                UUID groupId = project.get().getGroupId();
                // Check the PROJECT ceiling first.
                enforceCeiling(scope, Quota.Scope.PROJECT, projectId, resource, period, limitAmount);
                if (groupId != null) {
                    enforceCeiling(scope, Quota.Scope.GROUP, groupId, resource, period, limitAmount);
                }
                enforceCeilingAcrossAllOrgs(scope, resource, period, limitAmount);
            }
            case PROJECT -> {
                Optional<Project> project = projectRepository.findById(scopeId);
                if (project.isEmpty()) {
                    return;
                }
                UUID groupId = project.get().getGroupId();
                if (groupId != null) {
                    enforceCeiling(scope, Quota.Scope.GROUP, groupId, resource, period, limitAmount);
                }
                enforceCeilingAcrossAllOrgs(scope, resource, period, limitAmount);
            }
            case GROUP -> {
                enforceCeilingAcrossAllOrgs(scope, resource, period, limitAmount);
            }
        }
    }

    private void validateReservationFits(Quota.Scope scope, UUID scopeId, UUID projectId,
                                         Quota.ResourceType resource,
                                         Quota.Period period, long requestedAmount) {
        if (scope == Quota.Scope.ORG) {
            throw new IllegalArgumentException(
                    "ORG-level RESERVATION is not allowed; create a CEILING instead");
        }

        List<Quota> parentCeilings = findParentCeilings(scope, scopeId, projectId, resource, period);
        if (parentCeilings.isEmpty()) {
            throw new IllegalArgumentException(String.format(
                    "Reservations require an explicit budget at the parent scope: " +
                            "no parent CEILING exists to reserve from for %s/%s/%s",
                    scope, resource, period));
        }

        // Sum existing sibling reservations.  For SERVICE-scope reservations
        // the siblings are other SERVICE rows under the same project (matched
        // by the denormalised projectId column).  For GROUP / PROJECT
        // reservations the siblings share the parent's scopeType + scopeId.
        long alreadyReserved;
        if (scope == Quota.Scope.SERVICE && projectId != null) {
            alreadyReserved = quotaRepository.sumServiceReservationLimitByProject(
                    projectId, resource, period, null);
        } else {
            alreadyReserved = quotaRepository.sumReservationLimitByParent(
                    parentCeilings.get(0).getScopeType(),
                    parentCeilings.get(0).getScopeId(),
                    resource, period, null);
        }

        for (Quota ceiling : parentCeilings) {
            long remaining = ceiling.getLimitAmount() - alreadyReserved;
            if (requestedAmount > remaining) {
                throw new IllegalArgumentException(String.format(
                        "Reservation %d exceeds shared pool of %d for %s/%s under %s/%s " +
                                "(already reserved %d)",
                        requestedAmount, remaining, resource, period,
                        ceiling.getScopeType(), ceiling.getScopeId(), alreadyReserved));
            }
        }
    }

    private List<Quota> findParentCeilings(Quota.Scope scope, UUID scopeId, UUID projectId,
                                           Quota.ResourceType resource, Quota.Period period) {
        return switch (scope) {
            case ORG -> List.of();
            case GROUP -> quotaRepository
                    .findByScopeTypeAndResourceTypeAndPeriod(Quota.Scope.ORG, resource, period)
                    .stream().filter(q -> q.getQuotaType() == QuotaType.CEILING).toList();
            case SERVICE -> {
                // For SERVICE scope, resolve the project from the denormalised
                // projectId (resolved from the workflow/assistant instance).
                if (projectId == null) {
                    yield List.of();
                }
                Optional<Project> project = projectRepository.findById(projectId);
                if (project.isEmpty()) {
                    yield List.of();
                }
                UUID groupId = project.get().getGroupId();
                List<Quota> parents = new java.util.ArrayList<>();
                // SERVICE reservations are carved from the PROJECT ceiling.
                parents.addAll(quotaRepository
                        .findByScopeTypeAndScopeIdAndResourceType(
                                Quota.Scope.PROJECT, projectId, resource)
                        .stream().filter(q -> q.getQuotaType() == QuotaType.CEILING
                                && q.getPeriod() == period)
                        .toList());
                if (groupId != null) {
                    parents.addAll(quotaRepository
                            .findByScopeTypeAndScopeIdAndResourceType(
                                    Quota.Scope.GROUP, groupId, resource)
                            .stream().filter(q -> q.getQuotaType() == QuotaType.CEILING
                                    && q.getPeriod() == period)
                            .toList());
                }
                parents.addAll(quotaRepository
                        .findByScopeTypeAndResourceTypeAndPeriod(Quota.Scope.ORG, resource, period)
                        .stream().filter(q -> q.getQuotaType() == QuotaType.CEILING)
                        .toList());
                yield parents;
            }
            case PROJECT -> {
                Optional<Project> project = projectRepository.findById(scopeId);
                if (project.isEmpty()) {
                    yield List.of();
                }
                UUID groupId = project.get().getGroupId();
                List<Quota> parents = new java.util.ArrayList<>();
                if (groupId != null) {
                    parents.addAll(quotaRepository
                            .findByScopeTypeAndScopeIdAndResourceType(
                                    Quota.Scope.GROUP, groupId, resource)
                            .stream().filter(q -> q.getQuotaType() == QuotaType.CEILING
                                    && q.getPeriod() == period)
                            .toList());
                }
                parents.addAll(quotaRepository
                        .findByScopeTypeAndResourceTypeAndPeriod(Quota.Scope.ORG, resource, period)
                        .stream().filter(q -> q.getQuotaType() == QuotaType.CEILING)
                        .toList());
                yield parents;
            }
        };
    }

    private void enforceCeiling(Quota.Scope childScope, Quota.Scope parentScope, UUID parentScopeId,
                                Quota.ResourceType resource, Quota.Period period, long childLimit) {
        List<Quota> parentCeilings = quotaRepository
                .findByScopeTypeAndScopeIdAndResourceType(parentScope, parentScopeId, resource)
                .stream()
                .filter(q -> q.getQuotaType() == QuotaType.CEILING)
                .toList();
        for (Quota parent : parentCeilings) {
            if (parent.getPeriod() == period && childLimit > parent.getLimitAmount()) {
                throw new IllegalArgumentException(String.format(
                        "Quota %s/%d exceeds parent %s ceiling of %d for %s/%s",
                        childScope, childLimit, parentScope,
                        parent.getLimitAmount(), resource, period));
            }
        }
    }

    private void enforceCeilingAcrossAllOrgs(Quota.Scope childScope,
                                             Quota.ResourceType resource,
                                             Quota.Period period, long childLimit) {
        List<Quota> orgCeilings = quotaRepository
                .findByScopeTypeAndResourceTypeAndPeriod(Quota.Scope.ORG, resource, period)
                .stream()
                .filter(q -> q.getQuotaType() == QuotaType.CEILING)
                .toList();
        for (Quota org : orgCeilings) {
            if (childLimit > org.getLimitAmount()) {
                throw new IllegalArgumentException(String.format(
                        "Quota %s/%d exceeds ORG ceiling of %d for %s/%s",
                        childScope, childLimit, org.getLimitAmount(), resource, period));
            }
        }
    }

    private void recordQuotaChange(Quota q, long newLimitAmount) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("oldLimitAmount", q.getLimitAmount());
        change.put("newLimitAmount", newLimitAmount);
        change.put("quotaId", q.getId().toString());
        change.put("timestamp", Instant.now().toString());
        try {
            auditEventService.recordEvent(
                    ResourceType.QUOTA, q.getId(), AuditAction.QUOTA_LIMIT_CHANGED,
                    q.getScopeType().name(),
                    null,
                    null, "SYSTEM",
                    null, null, null, null, change);
        } catch (Exception ex) {
            log.warn("Quota change audit failed (continuing): {}", ex.getMessage());
        }
    }

    private void audit(String action, Quota q) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scopeType", q.getScopeType().name());
        payload.put("scopeId", q.getScopeId() != null ? q.getScopeId().toString() : null);
        payload.put("projectId", q.getProjectId() != null ? q.getProjectId().toString() : null);
        payload.put("resourceType", q.getResourceType().name());
        payload.put("period", q.getPeriod().name());
        payload.put("limitAmount", q.getLimitAmount());
        payload.put("enforced", q.isEnforced());
        payload.put("enforcementMode", q.getEnforcementMode().name());
        try {
            auditEventService.recordEvent(
                    ResourceType.QUOTA, q.getId(), action,
                    q.getScopeType().name(),
                    null,
                    null, "SYSTEM",
                    null, null, null, null, payload);
        } catch (Exception ex) {
            log.warn("Audit of {} failed (continuing): {}", action, ex.getMessage());
        }
    }
}
