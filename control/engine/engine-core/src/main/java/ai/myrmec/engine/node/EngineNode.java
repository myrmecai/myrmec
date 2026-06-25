package ai.myrmec.engine.node;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Engine replica self-registration row (agent-concurrency §9.7). Each
 * engine replica registers itself on startup, heartbeats on a timer, and a
 * reaper marks stale replicas {@code DOWN}. This is the DB-neutral
 * ownership registry the cross-node router resolves owner addresses from.
 *
 * <p>{@code nodeId} is a <b>natural key</b> — the pod's stable DNS name (or
 * configured id), infrastructure identity the replica knows about itself.
 * On a single node this table holds exactly one {@code UP} row and every
 * owner lookup resolves to "self".</p>
 */
@Entity
@Table(name = "engine_nodes")
@Getter
@Setter
@NoArgsConstructor
public class EngineNode {

    public enum Status { UP, DRAINING, DOWN }

    @Id
    @Column(name = "node_id", nullable = false, updatable = false, length = 255)
    private String nodeId;

    /** host:port the replica is reachable at, in-cluster (pod address). */
    @Column(name = "address", nullable = false, length = 255)
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.UP;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "last_heartbeat_at", nullable = false)
    private Instant lastHeartbeatAt;
}
