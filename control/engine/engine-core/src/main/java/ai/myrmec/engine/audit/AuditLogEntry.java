package ai.myrmec.engine.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 9b — append-only user-action audit row.
 *
 * <p>Distinct from {@code execution_snapshots} (engine-side events for
 * V2 replay) and from {@code audit_events} (legacy SPI sink for
 * Community/Enterprise audit writers): this table captures the
 * who/what/when of HUMAN-driven actions so admins can answer
 * compliance questions ("who granted Bob the OWNER role on project X
 * last month?") without trawling logs.</p>
 *
 * <p>By policy this table is append-only and has NO foreign keys on
 * {@code actorUserId} / {@code resourceId} so the audit trail
 * survives deletion of the referenced rows.</p>
 */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
public class AuditLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Null for SYSTEM-initiated actions (bootstrap, scheduled jobs). */
    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "action", nullable = false, length = 80)
    private String action;

    @Column(name = "resource_type", length = 50)
    private String resourceType;

    @Column(name = "resource_id")
    private UUID resourceId;

    /** Mirrors the role-grant scope vocabulary (GROUP / PROJECT / SYSTEM). */
    @Column(name = "scope_type", length = 20)
    private String scopeType;

    @Column(name = "scope_id")
    private UUID scopeId;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "request_id", length = 80)
    private String requestId;

    @Column(name = "payload_json", columnDefinition = "clob")
    private String payloadJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
