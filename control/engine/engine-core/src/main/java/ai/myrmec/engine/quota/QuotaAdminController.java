package ai.myrmec.engine.quota;

import ai.myrmec.engine.quota.dto.CreateQuotaRequest;
import ai.myrmec.engine.quota.dto.QuotaResponse;
import ai.myrmec.engine.quota.dto.UpdateQuotaRequest;
import ai.myrmec.engine.spi.quota.QuotaDecision;
import ai.myrmec.engine.spi.quota.QuotaPolicyEngine;
import ai.myrmec.engine.spi.quota.QuotaResourceType;
import ai.myrmec.engine.spi.quota.QuotaScope;
import ai.myrmec.engine.user.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class QuotaAdminController {

    private final QuotaService quotaService;
    private final QuotaPolicyEngine quotaPolicyEngine;

    @GetMapping
    public List<QuotaResponse> list(
            @RequestParam(required = false) String scopeType,
            @RequestParam(required = false) UUID scopeId) {
        if (scopeType != null && scopeId != null) {
            return quotaService.findByScope(Quota.Scope.valueOf(scopeType), scopeId).stream()
                    .map(QuotaResponse::from)
                    .toList();
        }
        // No filter: every row (admins only; not paginated yet — quota
        // counts are expected to stay small).
        return quotaService.findAll().stream().map(QuotaResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<QuotaResponse> create(
            @Valid @RequestBody CreateQuotaRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        Quota saved = quotaService.create(
                Quota.Scope.valueOf(req.getScopeType()),
                req.getScopeId(),
                Quota.ResourceType.valueOf(req.getResourceType()),
                Quota.Period.valueOf(req.getPeriod()),
                req.getLimitAmount(),
                req.isEnforced(),
                req.getTags(),
                principal != null ? principal.getUserId() : null);
        return ResponseEntity.status(HttpStatus.CREATED).body(QuotaResponse.from(saved));
    }

    @PutMapping("/{id}")
    public QuotaResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateQuotaRequest req) {
        Quota updated = quotaService.update(id, req.getLimitAmount(), req.isEnforced(), req.getTags());
        return QuotaResponse.from(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        quotaService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Phase 8c &mdash; pause a quota at 120% consumption. Requires admin to manually resume.
     */
    @PostMapping("/{id}/pause")
    public QuotaResponse pause(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        Quota paused = quotaService.pause(id, principal != null ? principal.getUserId() : null);
        return QuotaResponse.from(paused);
    }

    /**
     * Phase 8c &mdash; resume a paused quota, re-enabling consumption checks.
     */
    @PostMapping("/{id}/resume")
    public QuotaResponse resume(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
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
            @RequestParam String scopeType,
            @RequestParam UUID scopeId,
            @RequestParam String resourceType,
            @RequestParam(defaultValue = "0") long amount) {
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
}
