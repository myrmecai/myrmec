// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/**
 * One durable assignment delivery per orchestration dispatch (design
 * §16.3/§16.7). The transaction that creates the task attempt inserts this
 * row with the canonical assignment bytes, their SHA-256 digest, and a
 * {@code PENDING} delivery state — committed BEFORE any WebSocket send. The
 * relay retransmits these exact bytes until {@code inference.accept} flips
 * the row to {@code ACCEPTED} under a lock.
 *
 * <p>{@code dispatchId} equals the task attempt UUID in V1 (§16.2), making
 * the dispatch durable and unique without another database column.</p>
 */
@Entity
@Table(name = "orchestration_dispatches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrchestrationDispatch {

    /** == the task attempt UUID (V1 dispatchId, §16.2). */
    @Id
    @Column(name = "dispatch_id", nullable = false, updatable = false)
    private UUID dispatchId;

    /** The run (workflow request) this dispatch belongs to. */
    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    /** The task this dispatch executes. */
    @Column(name = "task_id", nullable = false, updatable = false)
    private UUID taskId;

    /** The complete canonical assignment JSON — retransmitted verbatim. */
    @Column(name = "assignment", nullable = false, columnDefinition = "text", updatable = false)
    private String assignment;

    /** SHA-256 over the canonical assignment bytes. */
    @Column(name = "assignment_digest", nullable = false, length = 64, updatable = false)
    private String assignmentDigest;

    /** PENDING → SENT → ACCEPTED. */
    @Column(name = "delivery_state", nullable = false, length = 20)
    @Builder.Default
    private String deliveryState = "PENDING";

    /** Number of WebSocket sends attempted (retransmissions included). */
    @Column(name = "send_count", nullable = false)
    @Builder.Default
    private Integer sendCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @jakarta.persistence.PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    /** Terminal delivery state check. */
    public boolean isAccepted() {
        return "ACCEPTED".equals(deliveryState);
    }
}