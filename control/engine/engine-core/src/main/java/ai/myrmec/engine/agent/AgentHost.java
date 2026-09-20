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
 * Agent Host entity - the machine (Supervisor process) that hosts many
 * Agents. Owns the registration key, advertised capacity, and governance
 * overlay. One running machine = one Agent Host.
 */
@Entity
@Table(name = "agent_hosts")
@Getter
@Setter
@NoArgsConstructor
public class AgentHost {

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
     * Advertised provisions used for reserve-time capability matching:
     * installed tools + runtime environment. Shape: { tools: [...], runtime: [...] }.
     */
    @Convert(converter = JsonMapConverter.class)
    @Column(name = "provisions")
    private Map<String, Object> provisions;

    /**
     * Operator hard ceiling on concurrent Agents (workers) this host may run.
     */
    @Column(name = "max_agents", nullable = false)
    private Integer maxAgents = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    /**
     * Durable host type (protocol §2.3) — replaces the former is_local
     * boolean. MANAGED for cluster/headless hosts, LOCAL for a user's IDE
     * supervisor, DEDICATED reserved.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "host_type", nullable = false, length = 20)
    private AgentHostType hostType = AgentHostType.MANAGED;

    /**
     * Per-host model access mode (credential-envelope design §5): DIRECT
     * calls the provider endpoint with the enveloped provider credential;
     * GATEWAY routes model calls through the org model gateway. Settable
     * only by PLATFORM_ADMIN (§5.1). Default DIRECT (dev-phase default).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "model_access_mode", nullable = false, length = 20)
    private ModelAccessMode modelAccessMode = ModelAccessMode.DIRECT;

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
