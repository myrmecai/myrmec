package ai.myrmec.engine.quota;

import ai.myrmec.engine.governance.GovernancePolicyEnforcer;
import ai.myrmec.engine.governance.GovernanceScope;
import ai.myrmec.engine.governance.ProductFeature;
import ai.myrmec.engine.quota.dto.CreateQuotaRequest;
import ai.myrmec.engine.quota.dto.QuotaResponse;
import ai.myrmec.engine.quota.dto.UpdateQuotaRequest;
import ai.myrmec.engine.spi.quota.QuotaDecision;
import ai.myrmec.engine.spi.quota.QuotaPolicyEngine;
import ai.myrmec.engine.spi.quota.QuotaResourceType;
import ai.myrmec.engine.spi.quota.QuotaScope;
import ai.myrmec.engine.quota.dto.BudgetPermissions;
import ai.myrmec.engine.user.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 8d &mdash; admin CRUD for {@link Quota} rows and a read-only
 * consumption probe used by the UI banner.
 *
 * <p>All mutating endpoints are gated by {@code PLATFORM_ADMIN}; the
 * consumption probe is also restricted to admins for now because a
 * project-scoped variant needs ACL plumbing that would belong in a
 * follow-up.
 */
@RestController
@RequestMapping("/api/v1/admin/quotas")
@RequiredArgsConstructor
public class QuotaAdminController {

    private final QuotaService quotaService;
    private final QuotaRepository quotaRepository;
    private final QuotaPolicyEngine quotaPolicyEngine;
    private final GovernancePolicyEnforcer governanceEnforcer;
    private final BudgetAuthorization budgetAuthorization;

    @GetMapping
    public List<QuotaResponse> list(
            @RequestParam(name = "scopeType", required = false) String scopeType,
            @RequestParam(name = "scopeId", required = false) UUID scopeId,
            Authentication authentication) {
        if (scopeType != null && scopeId != null) {
            Quota.Scope scope = Quota.Scope.valueOf(scopeType);
            requireView(scope, scopeId, authentication);
            return quotaService.findByScope(scope, scopeId).stream()
                    .map(QuotaResponse::from)
                    .toList();
        }
        // No filter: return only rows the caller is allowed to view.
        return quotaService.findAll().stream()
                .filter(q -> budgetAuthorization.permissionsFor(
                        q.getScopeType(), q.getScopeId(), authentication).canView())
                .map(QuotaResponse::from)
                .toList();
    }

    @PostMapping
    public ResponseEntity<QuotaResponse> create(
            @Valid @RequestBody CreateQuotaRequest req,
            Authentication authentication) {
        Quota.Scope scope = Quota.Scope.valueOf(req.getScopeType());
        requireCreate(scope, req, authentication);

        // Governance: BUDGET_OVERRIDE — reject if the profile doesn't allow this scope of override.
        // This is a threshold feature (NONE < PER_SERVICE < CONFIGURABLE): the profile's
        // value must permit at least PER_SERVICE for non-org overrides. Use
        // assertPermitted, not assertAllowed — CONFIGURABLE permits PER_SERVICE.
        if (scope != Quota.Scope.ORG) {
            governanceEnforcer.assertPermitted(
                req.getScopeId() != null ? GovernanceScope.ofProject(req.getScopeId()) : GovernanceScope.orgScope(),
                ProductFeature.BUDGET_OVERRIDE,
                "PER_SERVICE");
        }

        UserPrincipal principal = principalOf(authentication);
        Quota saved = quotaService.create(
                scope,
                req.getScopeId(),
                Quota.ResourceType.valueOf(req.getResourceType()),
                Quota.Period.valueOf(req.getPeriod()),
                req.getLimitAmount(),
                req.resolvedEnforcementMode(),
                req.resolvedQuotaType(),
                req.getServiceType() != null ? ServiceType.valueOf(req.getServiceType()) : null,
                req.getMaxExecutionAmount(),
                req.getTags(),
                principal != null ? principal.getUserId() : null);
        return ResponseEntity.status(HttpStatus.CREATED).body(QuotaResponse.from(saved));
    }

    @GetMapping("/{id}")
    public QuotaResponse get(@PathVariable("id") UUID id, Authentication authentication) {
        Quota q = quotaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Quota not found: " + id));
        if (!budgetAuthorization.permissionsFor(q.getScopeType(), q.getScopeId(), authentication).canView()) {
            throw new AccessDeniedException("Cannot view budget at " + q.getScopeType() + "/" + q.getScopeId());
        }
        return QuotaResponse.from(q);
    }

    @PutMapping("/{id}")
    public QuotaResponse update(
            @PathVariable("id") UUID id,
            @Valid @RequestBody UpdateQuotaRequest req,
            Authentication authentication) {
        Quota q = quotaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Quota not found: " + id));
        requireMutate(q, req.resolvedQuotaType(), authentication);

        // Governance: BUDGET_OVERRIDE — the create path already gated the scope,
        // and update does not change the quota's scope, so no enforcement is needed here.
        Quota updated = quotaService.update(
                id,
                req.getLimitAmount(),
                req.resolvedEnforcementMode(),
                req.resolvedQuotaType(),
                req.getMaxExecutionAmount(),
                req.getTags());
        return QuotaResponse.from(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") UUID id, Authentication authentication) {
        Quota q = quotaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Quota not found: " + id));
        BudgetPermissions perms = budgetAuthorization.permissionsFor(
                q.getScopeType(), q.getScopeId(), authentication);
        if (!perms.canDelete()) {
            throw new AccessDeniedException("Cannot delete budget at " + q.getScopeType() + "/" + q.getScopeId());
        }
        quotaService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Phase 8c &mdash; pause a quota at 120% consumption. Requires admin to manually resume.
     */
    @PostMapping("/{id}/pause")
    public QuotaResponse pause(
            @PathVariable("id") UUID id,
            Authentication authentication) {
        Quota q = quotaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Quota not found: " + id));
        BudgetPermissions perms = budgetAuthorization.permissionsFor(
                q.getScopeType(), q.getScopeId(), authentication);
        if (!perms.canPauseResume()) {
            throw new AccessDeniedException("Cannot pause budget at " + q.getScopeType() + "/" + q.getScopeId());
        }
        UserPrincipal principal = principalOf(authentication);
        Quota paused = quotaService.pause(id, principal != null ? principal.getUserId() : null);
        return QuotaResponse.from(paused);
    }

    /**
     * Phase 8c &mdash; resume a paused quota, re-enabling consumption checks.
     */
    @PostMapping("/{id}/resume")
    public QuotaResponse resume(
            @PathVariable("id") UUID id,
            Authentication authentication) {
        Quota q = quotaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Quota not found: " + id));
        BudgetPermissions perms = budgetAuthorization.permissionsFor(
                q.getScopeType(), q.getScopeId(), authentication);
        if (!perms.canPauseResume()) {
            throw new AccessDeniedException("Cannot resume budget at " + q.getScopeType() + "/" + q.getScopeId());
        }
        UserPrincipal principal = principalOf(authentication);
        Quota resumed = quotaService.resume(id, principal != null ? principal.getUserId() : null);
        return QuotaResponse.from(resumed);
    }

    /**
     * Returns the policy decision for the given scope/resource as if we
     * were about to charge {@code amount}. The UI uses this for the
     * 80% warning band and the 100% red banner.
     */
    @GetMapping("/consumption")
    public Map<String, Object> consumption(
            @RequestParam("scopeType") String scopeType,
            @RequestParam("scopeId") UUID scopeId,
            @RequestParam("resourceType") String resourceType,
            @RequestParam(name = "amount", defaultValue = "0") long amount,
            Authentication authentication) {
        Quota.Scope scope = Quota.Scope.valueOf(scopeType);
        requireView(scope, scopeId, authentication);
        QuotaDecision d = quotaPolicyEngine.check(
                QuotaScope.valueOf(scopeType),
                scopeId,
                QuotaResourceType.valueOf(resourceType),
                amount);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("blocked", d.isBlocked());
        body.put("warning", d.isWarning());
        body.put("limitAmount", d.getLimitAmount());
        body.put("consumedAmount", d.getConsumedAmount());
        body.put("remainingAmount", d.getRemainingAmount());
        body.put("scopeHit", d.getScopeHit() != null ? d.getScopeHit().name() : null);
        return body;
    }

    private void requireView(Quota.Scope scope, UUID scopeId, Authentication authentication) {
        if (!budgetAuthorization.permissionsFor(scope, scopeId, authentication).canView()) {
            throw new AccessDeniedException("Cannot view budgets at " + scope + "/" + scopeId);
        }
    }

    private void requireCreate(Quota.Scope scope, CreateQuotaRequest req, Authentication authentication) {
        BudgetPermissions perms = budgetAuthorization.permissionsFor(scope, req.getScopeId(), authentication);
        if (scope == Quota.Scope.SERVICE) {
            if (!perms.canCreateServiceBudget()) {
                throw new AccessDeniedException("Cannot create service budget at " + scope + "/" + req.getScopeId());
            }
            return;
        }
        QuotaType quotaType = req.resolvedQuotaType();
        if (quotaType == QuotaType.CEILING && !perms.canCreateCeiling()) {
            throw new AccessDeniedException("Cannot create ceiling at " + scope + "/" + req.getScopeId());
        }
        if (quotaType == QuotaType.RESERVATION && !perms.canCreateReservation()) {
            throw new AccessDeniedException("Cannot create reservation at " + scope + "/" + req.getScopeId());
        }
    }

    private void requireMutate(Quota q, QuotaType targetType, Authentication authentication) {
        BudgetPermissions perms = budgetAuthorization.permissionsFor(q.getScopeType(), q.getScopeId(), authentication);
        if (q.getScopeType() == Quota.Scope.SERVICE) {
            if (!perms.canCreateServiceBudget()) {
                throw new AccessDeniedException("Cannot update service budget at " + q.getScopeType() + "/" + q.getScopeId());
            }
            return;
        }
        if (targetType == QuotaType.CEILING && !perms.canCreateCeiling()) {
            throw new AccessDeniedException("Cannot update ceiling at " + q.getScopeType() + "/" + q.getScopeId());
        }
        if (targetType == QuotaType.RESERVATION && !perms.canCreateReservation()) {
            throw new AccessDeniedException("Cannot update reservation at " + q.getScopeType() + "/" + q.getScopeId());
        }
    }

    private static UserPrincipal principalOf(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        Object p = authentication.getPrincipal();
        return p instanceof UserPrincipal up ? up : null;
    }
}
