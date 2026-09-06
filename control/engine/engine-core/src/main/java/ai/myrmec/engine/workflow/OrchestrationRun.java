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
 * One durable orchestration run per orchestration workflow request (design
 * §16.7). The primary key IS the workflow request UUID — the run is the
 * request from the orchestration domain's perspective.
 *
 * <p>Carries the pinned Agent Profile version ({@code profileVersionId} FK
 * to {@code agent_profile_versions} plus its content digest) resolved at
 * request creation: later Profile publishes never affect an in-flight run
 * (§16.1). Also owns coordinator affinity (§16.4), the opaque Host workspace
 * identity (§17.1 — never a Host-local path), durable availability
 * throttling, and the pinned opaque recovery identity (§17.2).</p>
 *
 * <p>The engine never stores source bytes, diffs, prompts, command output,
 * Host-local paths, or encrypted recovery archives on this row.</p>
 */
@Entity
@Table(name = "orchestration_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrchestrationRun {

    /** == the workflow request UUID (§16.2: runId is the request ID). */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workflow_id", nullable = false, updatable = false)
    private UUID workflowId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    /** §16.1 pin: the published Profile version resolved at request creation. */
    @Column(name = "profile_version_id", nullable = false, updatable = false)
    private UUID profileVersionId;

    /** SHA-256 over the pinned version's canonical content — drift audit. */
    @Column(name = "profile_version_digest", nullable = false, length = 64, updatable = false)
    private String profileVersionDigest;

    // ── coordinator affinity (§16.4) ──────────────────────────────

    /** The logical Agent instance selected by the first dispatch. */
    @Column(name = "coordinator_agent_id")
    private UUID coordinatorAgentId;

    /** The coordinator's Host — affinity proof scope. */
    @Column(name = "coordinator_host_id")
    private UUID coordinatorHostId;

    // ── opaque Host workspace identity (§17.1) ───────────────────

    /** Host-supplied opaque workspace handle; never a path. */
    @Column(name = "workspace_id", length = 100)
    private String workspaceId;

    @Column(name = "workspace_generation")
    private Integer workspaceGeneration;

    /** Lease state: ACQUIRING/ACTIVE/SUSPENDED/RELEASING/RELEASED/LOST (§16.5). */
    @Column(name = "lease_state", length = 20)
    private String leaseState;

    @Column(name = "lease_deadline")
    private Instant leaseDeadline;

    // ── affinity recovery (§16.4) ─────────────────────────────────

    /**
     * First-loss recovery deadline; never extended by retries or heartbeats.
     * Expiry without same-Host lease proof is terminal WORKSPACE_LOST.
     */
    @Column(name = "affinity_recovery_deadline")
    private Instant affinityRecoveryDeadline;

    // ── durable availability throttling (§16.4) ───────────────────

    /** AVAILABLE | UNAVAILABLE. */
    @Column(name = "availability_state", length = 20)
    @Builder.Default
    private String availabilityState = "AVAILABLE";

    /** Increments on each available→unavailable transition. */
    @Column(name = "availability_episode")
    @Builder.Default
    private Integer availabilityEpisode = 0;

    /** Increments per eligible throttled pass within an episode. */
    @Column(name = "availability_occurrence")
    @Builder.Default
    private Integer availabilityOccurrence = 0;

    // ── pinned opaque recovery identity (§17.2) ───────────────────

    @Column(name = "recovery_provider_id", length = 100)
    private String recoveryProviderId;

    @Column(name = "recovery_provider_version", length = 50)
    private String recoveryProviderVersion;

    @Column(name = "recovery_config_digest", length = 64)
    private String recoveryConfigDigest;

    @Column(name = "current_continuation_id", length = 100)
    private String currentContinuationId;

    @Column(name = "current_snapshot_id", length = 100)
    private String currentSnapshotId;

    @Column(name = "current_snapshot_digest", length = 64)
    private String currentSnapshotDigest;

    @Column(name = "snapshot_expires_at")
    private Instant snapshotExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @jakarta.persistence.PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @jakarta.persistence.PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}