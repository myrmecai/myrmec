// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Feature 10 (design §16.5) → unified protocol (P6-T6): the legacy
 * {@code orchestration.release} push to the coordinator died with the legacy
 * agent wire. Under the unified protocol the host releases its checkout when
 * the run's session closes (§9 — host releases checkout on session close),
 * and the workspace-release expiry sweep reconciles anything missed. This
 * bean remains the single owner of "run reached terminal state" bookkeeping so
 * the outcome/approval/progression services keep one call site; it now only
 * logs the decision.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WorkspaceReleaseOrchestrator {

    private final OrchestrationRunRepository runRepository;
    private final OrchestrationAffinityResolver affinityResolver;

    /**
     * Record that a request's run reached a terminal state — COMPLETED,
     * FAILED, CANCELLED, or operator stop. Pure-inference requests have no
     * run row and no lease. Release itself happens via §9 session-close
     * semantics + the expiry sweep.
     *
     * @return true when the request is orchestrated (a run row exists)
     */
    public boolean releaseIfOrchestrated(WorkflowRequest request) {
        boolean orchestrated = runRepository.findById(request.getId()).isPresent();
        if (orchestrated) {
            release(request.getId());
        }
        return orchestrated;
    }

    /**
     * Log the terminal release for one run. Generation 1 in V1 (no
     * generation advance before release).
     */
    public boolean release(UUID runId) {
        boolean hasCoordinator = affinityResolver.coordinatorOf(runId).isPresent();
        if (!hasCoordinator) {
            log.info("Run {} has no coordinator — release handled by session close + expiry sweep",
                    runId);
            return false;
        }
        log.info("Run {} terminal — coordinator releases checkout on session close (§9); "
                + "expiry sweep reconciles", runId);
        return true;
    }
}