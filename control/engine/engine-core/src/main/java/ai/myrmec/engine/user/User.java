package ai.myrmec.engine.user;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Human user entity for authentication and authorization.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /**
     * BCrypt password hash. Null for non-LOCAL users.
     */
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Column(name = "provider_code", nullable = false, length = 50)
    private String providerCode = AuthenticationProvider.LOCAL_CODE;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    /**
     * System users cannot be edited or deleted.
     * The bootstrap admin user is marked as system user.
     */
    @Column(name = "is_system", nullable = false)
    private Boolean isSystem = false;

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

    /**
     * Check if user can authenticate with password (LOCAL auth).
     */
    public boolean canAuthenticateWithPassword() {
        return AuthenticationProvider.LOCAL_CODE.equals(providerCode) && passwordHash != null;
    }
}
