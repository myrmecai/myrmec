// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.audit;

import ai.myrmec.engine.audit.dto.AuditLogPageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * REST controller for querying the audit event log.
 *
 * <p>Maps to {@code GET /api/v1/audit-log} — the path the TypeScript
 * {@code auditLogApi.search()} client already calls. The endpoint was
 * previously unimplemented (returned 404), which made the J2 test's
 * audit verification step best-effort and blocked the J8 forensic audit
 * journey entirely.</p>
 *
 * <p>Authorization: {@code PLATFORM_ADMIN}, {@code ORG_ADMIN}, or
 * {@code AUDITOR}. This matches the {@code AUDITOR} role description:
 * "Read-only across data + audit + spend in scope."</p>
 */
@RestController
@RequestMapping("/api/v1/audit-log")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditEventService auditEventService;

    /** Maximum page size to prevent excessive queries. */
    private static final int MAX_PAGE_SIZE = 100;

    @GetMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'ORG_ADMIN', 'AUDITOR')")
    public ResponseEntity<AuditLogPageResponse> search(
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) UUID resourceId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false) Instant since,
            @RequestParam(required = false) Instant until,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        int clampedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        var pageable = PageRequest.of(page, clampedSize);

        var result = auditEventService.search(
                resourceType, resourceId, action, actorUserId,
                since, until, pageable);

        return ResponseEntity.ok(AuditLogPageResponse.from(result));
    }
}