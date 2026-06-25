package ai.myrmec.engine.serviceaccount;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * ServiceAccount — a machine principal that drives Conversation Sessions over
 * the External API ({@code /api/v1/external/*}, backlog #95) on behalf of its
 * own end-users.
 *
 * <p>Authenticated by Keycloak via the {@code client-credentials} grant: Myrmec
 * stores no secret, only {@link #keycloakClientId} (the token's client/azp
 * claim), which the external filter chain resolves to this row at request time.
 * A service account is scoped to exactly one {@code project}; the assistants it
 * may open all live in that project and are further gated by
 * {@code assistant_grants(USE)} ∩ {@code EXTERNAL_API ∈ usable_via}.</p>
 */
@Entity
@Table(name = "service_accounts")
@Getter
@Setter
@NoArgsConstructor
public class ServiceAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    /** Keycloak client/azp claim mapped to this account. Unique across the platform. */
    @Column(name = "keycloak_client_id", nullable = false, length = 255, updatable = false)
    private String keycloakClientId;

    /** Kill switch: false → external calls authenticating as this account are rejected 403. Reversible. */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** Per-account request rate limit (requests/minute). Enforced in #95a. */
    @Column(name = "rate_limit_per_min", nullable = false)
    private int rateLimitPerMin = 60;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
