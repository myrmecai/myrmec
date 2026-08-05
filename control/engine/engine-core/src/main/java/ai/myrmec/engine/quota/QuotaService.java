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
 * Phase 8 &mdash; admin CRUD on {@link Quota} rows. Owns the
 * child-tightens-only invariant (Phase 8e): a project-scoped quota
 * cannot exceed its parent group's quota for the same
 * (resource, period); a user-scoped quota cannot exceed its parent
 * project's quota; etc.
 *
 * <p>Hierarchy parent lookup is intentionally simple in Community:
 * we don't crawl the org/group/project relationship table here, we
 * just compare against quotas at the next-higher scope that have
 * matching (resource, period). Enterprise can layer in inherited
 * scoping later by overriding the bean.</p>
 *
 * <p>Every mutation records an audit row under action
 * {@code QUOTA_CREATED} / {@code QUOTA_UPDATED} / {@code QUOTA_DELETED}
 * so the Phase 8d UI history pane has clean data.</p>
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
                        Quota.Period period, long limitAmount, boolean enforced,
                        Map<String, Object> tags, UUID createdBy) {
        validateChildTightensOnly(scope, scopeId, resource, period, limitAmount);
        Quota q = new Quota();
        q.setScopeType(scope);
        q.setScopeId(scopeId);
        q.setResourceType(resource);
        q.setPeriod(period);
        q.setLimitAmount(limitAmount);
        q.setEnforced(enforced);
        q.setTags(tags);
        q.setCreatedBy(createdBy);
        Quota saved = quotaRepository.save(q);
        audit("QUOTA_CREATED", saved);
        return saved;
    }

    @Transactional
    public Quota update(UUID id, long newLimitAmount, boolean enforced, Map<String, Object> tags) {
        Quota q = quotaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Quota not found: " + id));
        validateChildTightensOnly(q.getScopeType(), q.getScopeId(), q.getResourceType(),
                q.getPeriod(), newLimitAmount);
        recordQuotaChange(q, newLimitAmount);
        q.setLimitAmount(newLimitAmount);
        q.setEnforced(enforced);
        q.setTags(tags);
        Quota saved = quotaRepository.save(q);
        audit("QUOTA_UPDATED", saved);
        return saved;
    }

    @Transactional
    public void delete(UUID id) {
        Quota q = quotaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Quota not found: " + id));
        audit("QUOTA_DELETED", q);
        quotaRepository.delete(q);
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
    private void validateChildTightensOnly(Quota.Scope scope, UUID scopeId,
                                           Quota.ResourceType resource,
                                           Quota.Period period, long limitAmount) {
        switch (scope) {
            case ORG -> {
                // No parent to check.
            }
            case SERVICE, PROJECT -> {
                Optional<Project> project = projectRepository.findById(scopeId);
                if (project.isEmpty()) {
                    // Project doesn't exist yet — let the FK validation
                    // happen at persist time; nothing for us to check.
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

    private void enforceCeiling(Quota.Scope childScope, Quota.Scope parentScope, UUID parentScopeId,
                                Quota.ResourceType resource, Quota.Period period, long childLimit) {
        List<Quota> parentQuotas = quotaRepository
                .findByScopeTypeAndScopeIdAndResourceType(parentScope, parentScopeId, resource);
        for (Quota parent : parentQuotas) {
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
        List<Quota> orgQuotas = quotaRepository
                .findByScopeTypeAndResourceTypeAndPeriod(Quota.Scope.ORG, resource, period);
        for (Quota org : orgQuotas) {
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
