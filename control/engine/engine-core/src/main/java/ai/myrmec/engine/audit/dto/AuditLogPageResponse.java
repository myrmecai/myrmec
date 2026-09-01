// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.audit.dto;

import ai.myrmec.engine.audit.AuditEvent;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Paginated response for the audit log endpoint. Matches the TypeScript
 * {@code AuditLogPage} interface shape.
 */
public record AuditLogPageResponse(
        List<AuditLogEntryResponse> items,
        long totalElements,
        int page,
        int size
) {
    public static AuditLogPageResponse from(Page<AuditEvent> page) {
        return new AuditLogPageResponse(
                page.getContent().stream()
                        .map(AuditLogEntryResponse::from)
                        .toList(),
                page.getTotalElements(),
                page.getNumber(),
                page.getSize()
        );
    }
}