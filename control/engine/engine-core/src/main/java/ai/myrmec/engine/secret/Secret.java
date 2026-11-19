package ai.myrmec.engine.secret;

import ai.myrmec.engine._system.common.JsonMapConverter;
import ai.myrmec.engine.team.Team;
import ai.myrmec.engine.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Secret entity — unified storage for both global (SYSTEM_ADMIN-managed) and
 * Team-scoped credentials.
 *
 * <p>Discriminators:
 * <ul>
 *   <li>{@code Team} {@code null} ⇒ global; non-null ⇒ Team-scoped.</li>
 *   <li>{@link #type} declares the payload shape (see {@link CredentialType}).</li>
 *   <li>{@link #backend} declares where the cleartext lives. For
 *       {@link SecretBackend#LOCAL} the AES-GCM ciphertext sits in
 *       {@link #encryptedValue}; for other backends, {@link #backendRef} carries
 *       the lookup coordinates (path, ARN, etc.).</li>
 * </ul>
 *
 * <p>Consumers reference a secret by its UUID {@link #id} — names are display
 * only and can be reused / shadowed between global and Team scope.
 */
@Entity
@Table(name = "secrets")
@Getter
@Setter
@NoArgsConstructor
public class Secret {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Null = global secret; non-null = team-scoped. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40)
    private CredentialType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "backend", nullable = false, length = 40)
    private SecretBackend backend = SecretBackend.LOCAL;

    /** Populated when {@link #backend} is {@link SecretBackend#LOCAL}. */
    @Column(name = "encrypted_value", columnDefinition = "bytea")
    private byte[] encryptedValue;

    /** Populated when {@link #backend} is not {@link SecretBackend#LOCAL}. */
    @Convert(converter = JsonMapConverter.class)
    @Column(name = "backend_ref")
    private Map<String, Object> backendRef;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public boolean isGlobal() {
        return team == null;
    }
}
