package ai.myrmec.engine.audit;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 9b — read-side projection for {@link AuditLogEntry} suitable
 * for the audit-log REST endpoint. Excludes nothing because the
 * endpoint is already restricted to PLATFORM_ADMIN.
 */
@Value
@Builder
public class AuditLogEntryResponse {
    UUID id;
    UUID actorUserId;
    String action;
    String resourceType;
    UUID resourceId;
    String scopeType;
    UUID scopeId;
    String ipAddress;
    String userAgent;
    String requestId;
    String payloadJson;
    Instant createdAt;

    public static AuditLogEntryResponse from(AuditLogEntry e) {
        return AuditLogEntryResponse.builder()
                .id(e.getId())
                .actorUserId(e.getActorUserId())
                .action(e.getAction())
                .resourceType(e.getResourceType())
                .resourceId(e.getResourceId())
                .scopeType(e.getScopeType())
                .scopeId(e.getScopeId())
                .ipAddress(e.getIpAddress())
                .userAgent(e.getUserAgent())
                .requestId(e.getRequestId())
                .payloadJson(e.getPayloadJson())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
