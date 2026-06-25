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
 * Agent entity - a single worker that serves one session at a time,
 * hosted by an Agent Host. Reports runtime info and heartbeat.
 */
@Entity
@Table(name = "agents")
@Getter
@Setter
@NoArgsConstructor
public class Agent {

    /**
     * Agent runtime FSM (agent-concurrency §9.5). {@code IDLE} = warm in the
     * pool, available to be reserved; {@code RESERVED/CONNECTING/BOUND/DRAINING}
     * = the reserve→serve→release lifecycle (transitions wired in a later
     * slice); {@code DEAD} = host/heartbeat lost, row reaped.
     */
    public enum Status {
        IDLE, RESERVED, CONNECTING, BOUND, DRAINING, DEAD
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Foreign key to agent_hosts table (the host running this agent).
     */
    @Column(name = "agent_host_id")
    private UUID agentHostId;

    /**
     * The conversation this agent is bound to (set at reserve-time). Null when idle.
     */
    @Column(name = "conversation_id")
    private UUID conversationId;

    /**
     * The agent profile version pinned at reserve-time. Null when idle.
     */
    @Column(name = "profile_version_id")
    private UUID profileVersionId;

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
    private Status status = Status.DEAD;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "registered_at", nullable = false, updatable = false)
    private Instant registeredAt;

    /**
     * When this worker last changed FSM {@link Status}. The reserve/connect
     * timeout reapers measure age from here (agent-concurrency §9.5): a worker
     * stuck in {@code RESERVED} or {@code CONNECTING} past the cutoff is
     * reclaimed to {@code IDLE}. Distinct from {@link #lastHeartbeatAt} (which
     * tracks liveness, not lifecycle progress).
     */
    @Column(name = "state_changed_at")
    private Instant stateChangedAt;

    /**
     * Replica holding this Agent's conversation socket (slice 4a). Null when
     * idle/unbound; on a single node it resolves to "self". The cross-node
     * router (slice 4b) reads this to forward turns to the owning replica.
     */
    @Column(name = "home_node_id", length = 255)
    private String homeNodeId;

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
        if (stateChangedAt == null) {
            stateChangedAt = Instant.now();
        }
    }

    /** Record the moment of the latest FSM transition (reaper clock). */
    private void touchState() {
        this.stateChangedAt = Instant.now();
    }

    /**
     * Record a heartbeat from this instance. A live heartbeat from a warm,
     * unbound worker keeps it {@code IDLE} (available in the pool); a
     * heartbeat from a worker that had been reaped to {@code DEAD} revives
     * it back into the pool. A worker that is mid-lifecycle
     * ({@code RESERVED}/{@code CONNECTING}/{@code BOUND}/{@code DRAINING})
     * keeps that status — a heartbeat must never yank a bound worker back
     * into the available pool (agent-concurrency §9.5).
     */
    public void recordHeartbeat() {
        this.lastHeartbeatAt = Instant.now();
        if (this.status == Status.IDLE || this.status == Status.DEAD) {
            this.status = Status.IDLE;
        }
    }

    /**
     * Release this worker from its current binding back into the warm pool:
     * {@code -> IDLE} with the conversation/profile-version binding cleared.
     * Idempotent — safe to call on an already-idle worker.
     */
    public void release() {
        this.status = Status.IDLE;
        this.conversationId = null;
        this.profileVersionId = null;
        this.homeNodeId = null;
        touchState();
    }

    /**
     * Mark this worker {@code CONNECTING} once the Agent Host has acked the
     * {@code agent.bind} and its worker is dialing the home node
     * (agent-concurrency §9.5). Resets the reaper clock so the connect-timeout
     * window is measured from the ack, not from the original reserve.
     */
    public void markConnecting() {
        this.status = Status.CONNECTING;
        touchState();
    }

    /**
     * Mark this worker {@code BOUND} after its conversation socket has
     * attached to the home node (agent-concurrency §9.4/§9.5). Pins the
     * replica that now holds the conversation socket so the cross-node
     * router can address turns to it.
     */
    public void markBound(String homeNodeId) {
        this.status = Status.BOUND;
        this.homeNodeId = homeNodeId;
        touchState();
    }

    /**
     * Re-home this worker after its home node was lost (agent-concurrency
     * §9.11): {@code BOUND → CONNECTING}, clearing {@code home_node_id} so the
     * cross-node router stops addressing the dead replica, and re-arming the
     * connect-timeout clock via {@link #touchState()} so a failed re-home is
     * reclaimed by the existing {@code CONNECT_TIMEOUT} reaper. Driven by the
     * {@code HomeNodeFailoverService} sweep when the worker's home replica is
     * marked {@code DOWN} but its agent host is still alive.
     */
    public void markReHoming() {
        this.status = Status.CONNECTING;
        this.homeNodeId = null;
        touchState();
    }

    /**
     * Mark this worker {@code DRAINING} — finishing its current work before
     * being released back to the pool (agent-concurrency §9.5). Used by the
     * graceful soft-release / host-drain paths.
     */
    public void markDraining() {
        this.status = Status.DRAINING;
        touchState();
    }

    /**
     * Mark this worker offline (control/connection closed). Removes it from
     * the warm pool by flipping it to {@code DEAD}.
     */
    public void markOffline() {
        this.status = Status.DEAD;
        touchState();
    }

    /**
     * Mark this worker dead after a transport error. Same terminal state as
     * {@link #markOffline()} — the new FSM has no separate error state.
     */
    public void markError() {
        this.status = Status.DEAD;
        touchState();
    }
}
