// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * REST endpoint for verifying audit chain integrity.
 *
 * <p>API-only in V1 (no UI). Runs on-demand — recomputes the entire chain
 * for the given scope. Role-gated: PLATFORM_ADMIN, ORG_ADMIN, or AUDITOR.
 *
 * <pre>{@code
 * GET /api/v1/admin/audit-log/verify?scopeType=PROJECT&projectId=<uuid>
 * → { "valid": true, "checkedCount": 42, "firstBrokenId": null, "reason": null }
 * }</pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/audit-log")
@RequiredArgsConstructor
public class AuditChainVerifyController {

    private final AuditHashChainService hashChainService;

    /**
     * Verify the integrity of a scope's audit chain.
     *
     * @param scopeType the scope type ("ORGANIZATION" or "PROJECT")
     * @param projectId the project ID (required for PROJECT scope, null for ORG)
     * @return verification result
     */
    @GetMapping("/verify")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','AUDITOR')")
    public ResponseEntity<Map<String, Object>> verify(
            @RequestParam String scopeType,
            @RequestParam(required = false) UUID projectId) {

        log.info("Verifying audit chain: scopeType={}, projectId={}", scopeType, projectId);

        AuditHashChainService.VerifyResult result = hashChainService.verifyScope(scopeType, projectId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("valid", result.valid());
        body.put("checkedCount", result.checkedCount());
        body.put("firstBrokenId", result.firstBrokenId() != null ? String.valueOf(result.firstBrokenId()) : null);
        body.put("reason", result.reason());

        return ResponseEntity.ok(body);
    }
}