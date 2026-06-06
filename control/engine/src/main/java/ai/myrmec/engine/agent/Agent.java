package ai.myrmec.engine.agent;

import ai.myrmec.engine._system.common.JsonMapConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Agent entity - deployable worker definition.
 * References a profile and owns a registration key.
 * Multiple instances can run from one agent definition.
 */
@Entity
@Table(name = "agents")
@Getter
@Setter
@NoArgsConstructor
public class Agent {

    public enum Status {
        ACTIVE, INACTIVE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Unique agent name (e.g., "Code Generator v2").
     */
    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    /**
     * Foreign key to agent_profiles table.
     */
    @Column(name = "profile_id", nullable = false)
    private UUID profileId;

    /**
     * Optional project scope. Null means system-wide.
     */
    @Column(name = "project_id")
    private UUID projectId;

    /**
     * Registration key for agent instances to authenticate.
     * Format: myr_agent_{base64}
     */
    @Column(name = "registration_key", nullable = false, unique = true, length = 255)
    private String registrationKey;

    /**
     * Override the profile's default model.
     */
    @Column(name = "model_override", length = 50)
    private String modelOverride;

    /**
     * Agent-specific configuration.
     */
    @Convert(converter = JsonMapConverter.class)
    @Column(name = "config")
    private Map<String, Object> config;

    /**
     * Maximum concurrent instances allowed.
     */
    @Column(name = "max_instances", nullable = false)
    private Integer maxInstances = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
