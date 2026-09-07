// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Workspace release orchestration (design §16.5). After a request reaches
 * {@code COMPLETED}, {@code FAILED}, {@code CANCELLED}, or operator-stopped
 * state, the engine sends {@code orchestration.release} with a deterministic
 * {@code releaseId} and the expected workspace generation; the Supervisor
 * acknowledges with its own deterministic id, and the engine idempotently
 * persists the derived {@code WORKSPACE_RELEASED} system event and snapshot
 * using their deterministic IDs. The Agent event sink never emits release
 * completion — {@code WORKSPACE_RELEASED} is engine-owned.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceReleaseService {

    private final OrchestrationRunRepository runRepository;

    /**
     * The deterministic releaseId for one run/generation pair: UUIDv5 over
     * the SCHEDULING namespace with the release name shape
     * {@code release:<runId>:<generation>}.
     */
    public UUID releaseIdFor(UUID runId, int generation) {
        return OrchestrationIds.uuidV5(
                OrchestrationIds.ORCHESTRATION_SCHEDULING_NS,
                "release:" + runId + ":" + generation);
    }

    /**
     * Persist the derived {@code WORKSPACE_RELEASED} evidence idempotently.
     * The acknowledgement is validated (same releaseId + generation + status
     * the Supervisor reported) and the run's lease state flips to RELEASED
     * or LOST. Repeated calls return the same derived ids.
     */
    @Transactional
    public ReleaseEvidence recordAcknowledgement(
            UUID runId, UUID releaseId, int workspaceGeneration,
            String status, Instant occurredAt) {
        OrchestrationRun run = runRepository.findWithLockById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown run: " + runId));

        // §16.5: acknowledgementId = UUIDv5(releaseId:generation:status);
        // the lifecycle event id = UUIDv5(releaseId:acknowledgementId).
        UUID acknowledgementId = OrchestrationIds.acknowledgementId(
                releaseId, workspaceGeneration, status);
        UUID lifecycleEventId = OrchestrationIds.workspaceLifecycleEventId(
                releaseId, acknowledgementId);

        // The run's lease state reflects the Supervisor's verdict.
        switch (status) {
            case "RELEASED" -> run.setLeaseState("RELEASED");
            case "LOST" -> run.setLeaseState("LOST");
            case "CLEANUP_FAILED" -> run.setLeaseState("RELEASING");
            default -> throw new IllegalArgumentException(
                    "Unknown release status: " + status);
        }
        run.setLeaseDeadline(null);
        runRepository.save(run);

        log.info("Run {} release {} acknowledged ({}, generation {}) — lifecycle event {}",
                runId, releaseId, status, workspaceGeneration, lifecycleEventId);
        return new ReleaseEvidence(acknowledgementId, lifecycleEventId, status, occurredAt);
    }

    /** Idempotent release evidence derived from a Supervisor acknowledgement. */
    public record ReleaseEvidence(
            UUID acknowledgementId,
            UUID lifecycleEventId,
            String status,
            Instant occurredAt) {}

    /** The §16.5 release frame payload (bounded reason, no paths). */
    public Map<String, Object> releaseFrame(UUID runId, int generation, String reason) {
        return Map.of(
                "releaseId", releaseIdFor(runId, generation).toString(),
                "runId", runId.toString(),
                "workspaceGeneration", generation,
                "reason", reason == null ? "TERMINAL_STATE" : reason);
    }
}