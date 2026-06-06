package ai.myrmec.engine.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;
import java.util.UUID;

/**
 * Phase 9b — central writer for {@link AuditLogEntry} rows.
 *
 * <p>Designed to be invoked from any service-layer method without
 * polluting the caller's transaction: every write runs in its own
 * {@link Propagation#REQUIRES_NEW} transaction so an audit failure
 * NEVER aborts the user's primary action. Exceptions are logged and
 * swallowed.</p>
 *
 * <p>HTTP request metadata (IP, user-agent, request id, current
 * principal) is sourced from the thread-bound
 * {@link RequestContextHolder} / {@link SecurityContextHolder} so
 * callers don't have to plumb anything; for non-HTTP code paths
 * (scheduled jobs, WebSocket handlers) the writer simply records
 * nulls and the entry will be tagged as a SYSTEM action when actor
 * is also null.</p>
 */
@Service
@Slf4j
public class AuditLogService {

    /** Maximum stored size of {@code payload_json} (post-serialisation). */
    public static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    private final AuditLogEntryRepository repository;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public AuditLogService(
            AuditLogEntryRepository repository,
            ObjectMapper objectMapper,
            @Value("${myrmec.audit-log.enabled:true}") boolean enabled) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    /**
     * Persist one audit row. NEVER throws; on failure the issue is
     * logged and the caller proceeds. Returns the persisted entity
     * when the row was written, an empty Optional when the writer is
     * disabled or the action is null.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<AuditLogEntry> record(AuditEvent event) {
        if (!enabled || event == null || event.getAction() == null) {
            return Optional.empty();
        }
        try {
            AuditLogEntry row = new AuditLogEntry();
            row.setAction(event.getAction());
            row.setActorUserId(resolveActor(event.getActorUserId()));
            row.setResourceType(event.getResourceType());
            row.setResourceId(event.getResourceId());
            row.setScopeType(event.getScopeType());
            row.setScopeId(event.getScopeId());
            row.setPayloadJson(serialise(event.getPayload()));

            HttpServletRequest req = currentRequest();
            if (req != null) {
                row.setIpAddress(clientIp(req));
                String ua = req.getHeader("User-Agent");
                if (ua != null && ua.length() > 500) {
                    ua = ua.substring(0, 500);
                }
                row.setUserAgent(ua);
                String rid = req.getHeader("X-Request-ID");
                if (rid == null || rid.isBlank()) {
                    rid = (String) req.getAttribute("requestId");
                }
                if (rid != null && rid.length() > 80) {
                    rid = rid.substring(0, 80);
                }
                row.setRequestId(rid);
            }
            return Optional.of(repository.save(row));
        } catch (Exception e) {
            log.warn("Audit log write failed for action {}: {}",
                    event.getAction(), e.getMessage(), e);
            return Optional.empty();
        }
    }

    private UUID resolveActor(UUID explicit) {
        if (explicit != null) {
            return explicit;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return null;
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof ai.myrmec.engine.user.UserPrincipal up) {
            return up.getUserId();
        }
        return null;
    }

    private String serialise(Object payload) {
        if (payload == null) {
            return null;
        }
        try {
            String json = payload instanceof String s
                    ? s
                    : objectMapper.writeValueAsString(payload);
            if (json != null && json.length() > MAX_PAYLOAD_BYTES) {
                // Soft cap — drop the entry rather than embed a giant
                // blob in the audit table.
                return json.substring(0, MAX_PAYLOAD_BYTES);
            }
            return json;
        } catch (Exception e) {
            log.warn("Failed to serialise audit payload of type {}: {}",
                    payload.getClass().getName(), e.getMessage());
            return null;
        }
    }

    private static HttpServletRequest currentRequest() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs == null ? null : attrs.getRequest();
        } catch (Exception e) {
            return null;
        }
    }

    private static String clientIp(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            int comma = fwd.indexOf(',');
            String first = (comma >= 0 ? fwd.substring(0, comma) : fwd).trim();
            return first.length() > 45 ? first.substring(0, 45) : first;
        }
        String addr = req.getRemoteAddr();
        return addr != null && addr.length() > 45 ? addr.substring(0, 45) : addr;
    }

    /** Inputs to {@link AuditLogService#record}. Use the builder. */
    @lombok.Value
    @Builder
    public static class AuditEvent {
        String action;
        UUID actorUserId;
        String resourceType;
        UUID resourceId;
        String scopeType;
        UUID scopeId;
        Object payload;
    }
}
