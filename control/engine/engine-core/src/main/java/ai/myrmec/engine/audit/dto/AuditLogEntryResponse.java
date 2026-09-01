// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.audit.dto;

import ai.myrmec.engine.audit.AuditEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * REST DTO for a single audit log entry. Maps the internal {@link AuditEvent}
 * entity to the TypeScript {@code AuditLogEntry} interface shape.
 *
 * <p>Fields {@code ipAddress}, {@code userAgent}, and {@code requestId} are
 * always {@code null} — the engine does not currently capture request-level
 * metadata for audit events. They are included for forward compatibility
 * with the TypeScript client interface.</p>
 */
public record AuditLogEntryResponse(
        String id,
        String actorUserId,
        String action,
        String resourceType,
        String resourceId,
        String scopeType,
        String scopeId,
        String ipAddress,
        String userAgent,
        String requestId,
        String payloadJson,
        String createdAt
) {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static AuditLogEntryResponse from(AuditEvent e) {
        String payloadJson = null;
        // Serialize afterSnapshot if present; fall back to metadata (some
        // services store the payload in metadata instead of afterSnapshot).
        Map<String, Object> payload = e.getAfterSnapshot();
        if (payload == null || payload.isEmpty()) {
            payload = e.getMetadata();
        }
        if (payload != null && !payload.isEmpty()) {
            try {
                payloadJson = objectMapper.writeValueAsString(payload);
            } catch (Exception ex) {
                // Omit payload on serialization failure
            }
        }
        return new AuditLogEntryResponse(
                e.getId().toString(),
                e.getActorId() != null ? e.getActorId().toString() : null,
                e.getEventType(),
                e.getEntityType(),
                e.getEntityId() != null ? e.getEntityId().toString() : null,
                e.getScopeType(),
                e.getProjectId() != null ? e.getProjectId().toString() : null,
                null,  // ipAddress — not stored
                null,  // userAgent — not stored
                null,  // requestId — not stored
                payloadJson,
                e.getTimestamp() != null ? e.getTimestamp().toString() : null
        );
    }
}