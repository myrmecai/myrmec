package ai.myrmec.engine.setting;

import jakarta.persistence.Column;
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
import java.util.UUID;

/**
 * #71a &mdash; one typed platform-configuration row.
 *
 * <p>Keys are well-known, stable identifiers (e.g.
 * {@code summarizer_model_code}, {@code history_limit}) so the key is the
 * natural primary key, following the reference/config-data convention.
 * The raw {@code value} is stored as text and interpreted by
 * {@link SettingType}; a blank value means "unset &mdash; use the
 * code-side default".</p>
 */
@Entity
@Table(name = "system_settings")
@Getter
@Setter
@NoArgsConstructor
public class SystemSetting {

    @Id
    @Column(name = "setting_key", nullable = false, updatable = false, length = 120)
    private String key;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, length = 20)
    private SettingType valueType;

    @Column(name = "setting_value", columnDefinition = "clob")
    private String value;

    @Column(name = "description", length = 500)
    private String description;

    /** Null for seeded / SYSTEM-set values. */
    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
