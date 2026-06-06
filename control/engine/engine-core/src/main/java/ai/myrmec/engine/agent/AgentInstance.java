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
 * Agent Instance entity - a running container of an agent.
 * Registers using agent's registration key, reports runtime info.
 */
@Entity
@Table(name = "agent_instances")
@Getter
@Setter
@NoArgsConstructor
public class AgentInstance {

    public enum Status {
        ONLINE, OFFLINE, ERROR
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Foreign key to agents table.
     */
    @Column(name = "agent_id")
    private UUID agentId;

    /**
     * Machine hostname where instance is running.
     */
    @Column(name = "hostname", length = 255)
    private String hostname;

    /**
     * IPv4 or IPv6 address.
     */
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    /**
     * Actual SDK version running on this instance.
     */
    @Column(name = "runtime_version", length = 20)
    private String runtimeVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.OFFLINE;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "registered_at", nullable = false, updatable = false)
    private Instant registeredAt;

    /**
     * Extra runtime metadata.
     */
    @Convert(converter = JsonMapConverter.class)
    @Column(name = "metadata")
    private Map<String, Object> metadata;

    @PrePersist
    protected void onCreate() {
        if (registeredAt == null) {
            registeredAt = Instant.now();
        }
    }

    /**
     * Record a heartbeat from this instance.
     */
    public void recordHeartbeat() {
        this.lastHeartbeatAt = Instant.now();
        this.status = Status.ONLINE;
    }

    /**
     * Mark instance as offline.
     */
    public void markOffline() {
        this.status = Status.OFFLINE;
    }

    /**
     * Mark instance as error state.
     */
    public void markError() {
        this.status = Status.ERROR;
    }
}
