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

    /**
     * CPU/RAM the Supervisor auto-sized its warm pool from. Host-reported,
     * re-asserted on each control-socket announce.
     */
    @Convert(converter = JsonMapConverter.class)
    @Column(name = "reported_capacity")
    private Map<String, Object> reportedCapacity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    /**
     * Replica currently holding this Host's control socket (slice 4a). Null
     * when no control socket is attached; on a single node it resolves to
     * "self". The cross-node router (slice 4b) reads this to forward Host
     * control messages to the owning replica.
     */
    @Column(name = "control_node_id", length = 255)
    private String controlNodeId;

    /**
     * Session-credential PSK identity (design §16.1/§17.2 — Feature 10).
     * The engine generates a 32-byte PSK with this keyId at Host
     * registration and returns it ONCE in the creation response over TLS;
     * only the EncryptionService-encrypted copy persists.
     */
    @Column(name = "psk_key_id", length = 64)
    private String pskKeyId;

    /** At-rest-encrypted PSK (never the raw key after creation). */
    @Column(name = "psk_encrypted", columnDefinition = "bytea")
    private byte[] pskEncrypted;

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
