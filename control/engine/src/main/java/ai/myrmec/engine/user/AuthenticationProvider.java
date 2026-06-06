package ai.myrmec.engine.user;

import ai.myrmec.engine._system.common.JsonMapConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Authentication provider configuration.
 */
@Entity
@Table(name = "authentication_providers")
@Getter
@Setter
@NoArgsConstructor
public class AuthenticationProvider {

    public static final String LOCAL_CODE = "LOCAL";

    public enum ProviderType {
        LOCAL,
        OIDC,
        GITHUB,
        GOOGLE
    }

    @Id
    @Column(name = "code", nullable = false, updatable = false, length = 50)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type", nullable = false, length = 20)
    private ProviderType providerType;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "is_enabled", nullable = false)
    private Boolean isEnabled = false;

    @Column(name = "is_system", nullable = false)
    private Boolean isSystem = false;

    @Convert(converter = JsonMapConverter.class)
    @Column(name = "metadata", columnDefinition = "text")
    private Map<String, Object> metadata = new HashMap<>();

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

    public boolean isLocal() {
        return LOCAL_CODE.equals(code) || providerType == ProviderType.LOCAL;
    }
}
