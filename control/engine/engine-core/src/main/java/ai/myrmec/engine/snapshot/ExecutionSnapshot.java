package ai.myrmec.engine.snapshot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 9a — one row per engine-side event for V2 replay history.
 *
 * <p>Append-only. The schema deliberately carries both an inline
 * {@link #payloadJson} and a reserved-for-V1.1 {@link #payloadUri} +
 * {@link #payloadSha256} pair so the future tiered-storage migration
 * (large payloads to object storage) is a code-only change.</p>
 *
 * <p>{@link #eventType} is a free-form string (e.g.
 * {@code CONVERSATION_TURN_DISPATCHED},
 * {@code APPROVAL_REQUESTED}, {@code APPROVAL_DECIDED}). The writer
 * trusts the caller — no enum so new event types don't require schema
 * migrations.</p>
 */
@Entity
@Table(name = "execution_snapshots")
@Getter
@Setter
@NoArgsConstructor
public class ExecutionSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "agent_id")
    private UUID agentId;

    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(name = "message_id")
    private UUID messageId;

    @Column(name = "workflow_id")
    private UUID workflowId;

    @Column(name = "step_run_id")
    private UUID stepRunId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    /**
     * Inline payload (subject to {@code SnapshotWriter}'s 256KB cap).
     * Null when the row has been migrated to external storage and
     * {@link #payloadUri} is set instead.
     */
    @Column(name = "payload_json", columnDefinition = "clob")
    private String payloadJson;

    /** Reserved for V1.1 tiered storage. Null in V1. */
    @Column(name = "payload_uri", length = 500)
    private String payloadUri;

    /** Reserved for V1.1 tiered storage (hex sha256). Null in V1. */
    @Column(name = "payload_sha256", length = 64)
    private String payloadSha256;

    /** True payload size in bytes (before truncation). */
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** True if the inline {@link #payloadJson} was clipped by the writer. */
    @Column(name = "truncated", nullable = false)
    private boolean truncated;

    /**
     * False when the writer kept the row only because the sampling rate
     * happened to fall in the row's favour despite a tighter setting.
     * Currently the writer always sets this to true; the column is
     * future-proofing for cost-aware sampling policies.
     */
    @Column(name = "sampled", nullable = false)
    private boolean sampled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
