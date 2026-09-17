// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

import ai.myrmec.engine._system.common.JsonMapConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One live supervisor run of an {@link AgentHost} — the runtime layer of the
 * unified protocol (§3/§19.1). Append-only (design 2026-09-11 §2.5, R11):
 * every registration inserts a new row; a disconnect {@link #close(String)}s
 * it; no row is ever reopened or reused, so the table is a history of
 * supervisor runs.
 *
 * <p>No setters for lifecycle state — {@link #close(String)} is the only
 * terminal transition and is idempotent/write-once; {@link #markHeartbeat()}
 * is the only liveness mutator. This is deliberate: append-only must be
 * enforced by the entity, not by convention.</p>
 */
@Entity
@Table(name = "agent_host_instances")
@Getter
@NoArgsConstructor
public class AgentHostInstance {

    public enum Status { OPEN, CLOSED }

    /** The sentinel live_key value; the unique index relies on it. */
    static final String LIVE = "OPEN";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "agent_host_id", nullable = false, updatable = false)
    private UUID agentHostId;

    /** LOCAL/DEDICATED owner; NULL for MANAGED hosts (§2.3). */
    @Column(name = "owner_user_id", updatable = false)
    private UUID ownerUserId;

    /**
     * Supervisor-run identity (§6.1): generated once per process run.
     * Replaying host.open with the same nonce is idempotent; a new nonce
     * supersedes the live instance.
     */
    @Column(name = "instance_nonce", length = 36, updatable = false)
    private String instanceNonce;

    @Column(name = "hostname", length = 255)
    private String hostname;

    @Column(name = "pool_size", nullable = false)
    private Integer poolSize = 1;

    public void setPoolSize(Integer poolSize) {
        this.poolSize = poolSize;
    }

    @Convert(converter = JsonMapConverter.class)
    @Column(name = "reported_capacity")
    private Map<String, Object> reportedCapacity;

    @Column(name = "control_node_id", length = 255)
    private String controlNodeId;

    /**
     * Per-run PSK identity (credential-envelope design §6): a fresh 32-byte
     * key is minted at host.open for THIS supervisor run; host.opened
     * delivers the plaintext once and the row keeps only the
     * EncryptionService-encrypted copy. Key lifetime == instance lifetime.
     */
    @Column(name = "psk_key_id")
    private UUID pskKeyId;

    /** At-rest-encrypted PSK (never the plaintext key after creation). */
    @Column(name = "psk_encrypted", columnDefinition = "bytea")
    private byte[] pskEncrypted;

    /**
     * Bind the freshly minted (or idempotently re-delivered) run key.
     * Deliberately NOT part of the append-only factory: the minting happens
     * after the row exists, in host.open handling.
     */
    public void setPsk(UUID keyId, byte[] encrypted) {
        this.pskKeyId = keyId;
        this.pskEncrypted = encrypted;
    }

    /**
     * Key erasure at rest (design §6): close(reason) nulls psk_encrypted;
     * the closed row keeps psk_key_id for audit.
     */
    public void erasePsk() {
        this.pskEncrypted = null;
        // pskKeyId intentionally retained for audit.
    }

    /**
     * §20 routing identity (A3, decision H5): the node whose socket serves
     * this run — the same value {@code host.opened.serverNodeId} reported at
     * open time. A same-nonce replay answered by a different replica means
     * the socket moved: re-stamp so peer relays target the socket's CURRENT
     * node, not the one that minted the row.
     */
    public void rehomeTo(String nodeId) {
        this.controlNodeId = nodeId;
    }

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.OPEN;

    /** 'OPEN' while live, NULL once closed — derived, never business state. */
    @Column(name = "live_key", length = 8)
    private String liveKey;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "close_reason", length = 100)
    private String closeReason;

    private AgentHostInstance(AgentHost host, UUID ownerUserId, String hostname,
                              int poolSize, Map<String, Object> reportedCapacity,
                              String controlNodeId) {
        this.agentHostId = host.getId();
        this.ownerUserId = ownerUserId;
        this.hostname = hostname;
        this.poolSize = poolSize;
        this.reportedCapacity = reportedCapacity;
        this.controlNodeId = controlNodeId;
        this.status = Status.OPEN;
    }

    /** Factory for a new supervisor run — always a NEW row (append-only). */
    public static AgentHostInstance open(AgentHost host, UUID ownerUserId, String hostname,
                                         int poolSize, Map<String, Object> reportedCapacity,
                                         String controlNodeId) {
        return open(host, ownerUserId, null, hostname, poolSize, reportedCapacity, controlNodeId);
    }

    /** Full factory including the supervisor-run nonce (§6.1). */
    public static AgentHostInstance open(AgentHost host, UUID ownerUserId, String instanceNonce,
                                         String hostname, int poolSize,
                                         Map<String, Object> reportedCapacity, String controlNodeId) {
        AgentHostInstance instance = new AgentHostInstance(host, ownerUserId, hostname,
                poolSize, reportedCapacity, controlNodeId);
        instance.instanceNonce = instanceNonce;
        return instance;
    }

    @PrePersist
    void onCreate() {
        if (openedAt == null) {
            openedAt = Instant.now();
        }
        if (status == Status.OPEN && liveKey == null) {
            liveKey = LIVE;
        }
    }

    /**
     * Terminal close. Idempotent and write-once: a second call is a no-op and
     * never overwrites {@code closedAt} or {@code closeReason} — history stays
     * truthful (design §3.2). Key erasure at rest rides the same transition:
     * {@code psk_encrypted} is nulled; {@code psk_key_id} is retained for
     * audit (credential-envelope design §6).
     */
    public void close(String reason) {
        if (closedAt != null) {
            return;
        }
        this.status = Status.CLOSED;
        this.liveKey = null;
        this.closedAt = Instant.now();
        this.closeReason = reason != null && reason.length() > 100
                ? reason.substring(0, 100) : reason;
        this.pskEncrypted = null;
    }

    /** Liveness signal; never changes lifecycle state. */
    public void markHeartbeat() {
        this.lastHeartbeatAt = Instant.now();
    }
}
