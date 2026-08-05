package ai.myrmec.engine.quota;

import ai.myrmec.engine.audit.AuditEventService;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectRepository;
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
        validateBudgetCreateOrUpdate(scope, scopeId, resource, period, limitAmount, quotaType);
        Quota q = new Quota();
        q.setScopeType(scope);
        q.setScopeId(scopeId);
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
        audit("QUOTA_CREATED", saved);
        return saved;
    }

    @Transactional
    public Quota update(UUID id, long newLimitAmount, EnforcementMode enforcementMode,
                        QuotaType quotaType, Long maxExecutionAmount, Map<String, Object> tags) {
        Quota q = quotaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Quota not found: " + id));
        validateBudgetCreateOrUpdate(q.getScopeType(), q.getScopeId(), q.getResourceType(),
                q.getPeriod(), newLimitAmount, quotaType);
        recordQuotaChange(q, newLimitAmount);
        q.setLimitAmount(newLimitAmount);
        q.setEnforcementMode(enforcementMode);
        q.setEnforced(enforcementMode == EnforcementMode.BLOCK);
        q.setQuotaType(quotaType);
        q.setMaxExecutionAmount(maxExecutionAmount);
        q.setTags(tags);
        Quota saved = quotaRepository.save(q);
        audit("QUOTA_UPDATED", saved);
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
        audit("QUOTA_DELETED", q);
        quotaRepository.delete(q);
    }

    private List<Quota> findChildReservationsForCeiling(Quota ceiling) {
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
        audit("QUOTA_PAUSED", saved);
        return saved;
    }

    @Transactional
    public Quota resume(UUID id, UUID resumedBy) {
        Quota q = quotaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Quota not found: " + id));
        q.setPausedAt(null);
        q.setPausedBy(null);
        Quota saved = quotaRepository.save(q);
        audit("QUOTA_RESUMED", saved);
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
    private void validateBudgetCreateOrUpdate(Quota.Scope scope, UUID scopeId,
                                              Quota.ResourceType resource,
                                              Quota.Period period, long limitAmount,
                                              QuotaType quotaType) {
        if (quotaType == QuotaType.RESERVATION) {
            validateReservationFits(scope, scopeId, resource, period, limitAmount);
            return;
        }
        switch (scope) {
            case ORG -> {
                // Top of hierarchy — no parent to check.
            }
            case SERVICE, PROJECT -> {
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

    private void validateReservationFits(Quota.Scope scope, UUID scopeId,
                                         Quota.ResourceType resource,
                                         Quota.Period period, long requestedAmount) {
        if (scope == Quota.Scope.ORG) {
            throw new IllegalArgumentException(
                    "ORG-level RESERVATION is not allowed; create a CEILING instead");
        }

        List<Quota> parentCeilings = findParentCeilings(scope, scopeId, resource, period);
        if (parentCeilings.isEmpty()) {
            throw new IllegalArgumentException(String.format(
                    "Reservations require an explicit budget at the parent scope: " +
                            "no parent CEILING exists to reserve from for %s/%s/%s",
                    scope, resource, period));
        }

        long alreadyReserved = quotaRepository
                .sumReservationLimitByParent(parentCeilings.get(0).getScopeType(),
                        parentCeilings.get(0).getScopeId(),
                        resource, period, null);

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

    private List<Quota> findParentCeilings(Quota.Scope scope, UUID scopeId,
                                           Quota.ResourceType resource, Quota.Period period) {
        return switch (scope) {
            case ORG -> List.of();
            case GROUP -> quotaRepository
                    .findByScopeTypeAndResourceTypeAndPeriod(Quota.Scope.ORG, resource, period)
                    .stream().filter(q -> q.getQuotaType() == QuotaType.CEILING).toList();
            case PROJECT, SERVICE -> {
                Optional<Project> project = projectRepository.findById(scopeId);
                if (project.isEmpty()) {
                    yield List.of();
                }
                UUID groupId = project.get().getGroupId();
                List<Quota> parents = new java.util.ArrayList<>();
                if (scope == Quota.Scope.SERVICE) {
                    parents.addAll(quotaRepository
                            .findByScopeTypeAndScopeIdAndResourceType(
                                    Quota.Scope.PROJECT, scopeId, resource)
                            .stream().filter(q -> q.getQuotaType() == QuotaType.CEILING
                                    && q.getPeriod() == period)
                            .toList());
                }
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
                    "Quota", q.getId(), "QUOTA_LIMIT_CHANGED",
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
        payload.put("scopeId", q.getScopeId().toString());
        payload.put("resourceType", q.getResourceType().name());
        payload.put("period", q.getPeriod().name());
        payload.put("limitAmount", q.getLimitAmount());
        payload.put("enforced", q.isEnforced());
        try {
            auditEventService.recordEvent(
                    "Quota", q.getId(), action,
                    q.getScopeType().name(),
                    null,
                    null, "SYSTEM",
                    null, null, null, null, payload);
        } catch (Exception ex) {
            log.warn("Audit of {} failed (continuing): {}", action, ex.getMessage());
        }
    }
}
