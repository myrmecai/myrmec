// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * §16.5/§16.6 release reconciliation — the cancellation/release
 * acknowledgement TIMEOUT. Terminal requests (COMPLETED/FAILED/CANCELLED)
 * whose run lease is still live (ACTIVE/ACQUIRING/SUSPENDED/RELEASING)
 * beyond the acknowledgement window are force-reconciled: the release
 * frame was sent (or could not be) at the terminal transition, and the
 * agent never acknowledged. The engine must not keep a lease alive
 * waiting for an ack that will not arrive: the run lease is marked
 * terminal so the Host-side grace sweep can reclaim the checkout.
 */
@Service
@Slf4j
public class WorkspaceReleaseReconciliationSweeper {

    /** How long a terminal run's lease may outlive its terminal state. */
    @Value("${myrmec.orchestration.release-ack-timeout-seconds:300}")
    private long releaseAckTimeoutSeconds;

    private final OrchestrationRunRepository runRepository;
    private final WorkflowRequestRepository requestRepository;

    public WorkspaceReleaseReconciliationSweeper(
            OrchestrationRunRepository runRepository,
            WorkflowRequestRepository requestRepository) {
        this.runRepository = runRepository;
        this.requestRepository = requestRepository;
    }

    /**
     * Reconciliation pass — direct invocation in tests; scheduled in
     * production.
     */
    @Scheduled(fixedRateString = "${myrmec.orchestration.release-reconciler.interval-ms:60000}")
    @Transactional
    public void reconcileUnacknowledgedReleases() {
        Instant cutoff = Instant.now().minus(Duration.ofSeconds(releaseAckTimeoutSeconds));
        List<OrchestrationRun> liveLeases = runRepository.findByLeaseStateIn(
                List.of("ACTIVE", "ACQUIRING", "SUSPENDED", "RELEASING"));
        for (OrchestrationRun run : liveLeases) {
            WorkflowRequest request = requestRepository.findById(run.getId()).orElse(null);
            if (request == null || request.getCompletedAt() == null) {
                continue; // not terminal (or unknown) — renewal owns it
            }
            if (request.getCompletedAt().isAfter(cutoff)) {
                continue; // still inside the acknowledgement window
            }
            // The window elapsed without an acknowledgement: the lease is
            // force-expired so the Host's grace sweep reclaims the disk.
            // The run is NOT marked RELEASED — no supervisor confirmed
            // cleanup — it is terminal-pending (LOST from the engine's
            // ownership perspective: the checkout may or may not still
            // exist, but the engine no longer claims it).
            run.setLeaseState("LOST");
            run.setLeaseDeadline(null);
            runRepository.save(run);
            log.warn("Run {} terminal since {} without a release acknowledgement — "
                            + "lease force-expired (ack timeout {}s)",
                    run.getId(), request.getCompletedAt(), releaseAckTimeoutSeconds);
        }
    }
}