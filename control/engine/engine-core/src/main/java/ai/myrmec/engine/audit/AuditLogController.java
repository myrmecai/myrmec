package ai.myrmec.engine.audit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 9b — read-only audit log browser. Restricted to PLATFORM_ADMIN
 * since the data is sensitive.
 */
@RestController
@RequestMapping("/api/v1/audit-log")
@RequiredArgsConstructor
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
@Tag(name = "Audit Log", description = "Browse user-action audit log entries")
public class AuditLogController {

    private final AuditLogEntryRepository repository;

    @Operation(summary = "Search audit-log entries (newest first)")
    @GetMapping
    public Map<String, Object> search(
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) UUID resourceId,
            @RequestParam(required = false) Instant since,
            @RequestParam(required = false) Instant until,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 200));
        Page<AuditLogEntry> result = repository.search(
                actorUserId, action, resourceType, resourceId, since, until, pageable);
        List<AuditLogEntryResponse> rows = result.stream()
                .map(AuditLogEntryResponse::from)
                .toList();
        return Map.of(
                "items", rows,
                "totalElements", result.getTotalElements(),
                "page", page,
                "size", pageable.getPageSize());
    }
}
