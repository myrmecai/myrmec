// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import ai.myrmec.engine.websocket.AgentWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Feature 10 (design §16.5): sends the deterministic
 * {@code orchestration.release} frame to a run's coordinator after every
 * terminal request state — COMPLETED, FAILED, CANCELLED, or operator
 * stop. The Supervisor's idempotent acknowledgement (over
 * {@code host.announce}) flips the run's lease state; the expiry sweep
 * reconciles anything the best-effort send misses.
 *
 * <p>One owner for the send path keeps the outcome service and the
 * progression service from duplicating frame logic. The websocket
 * handler is resolved through an {@link ObjectProvider} (deferred
 * lookup): the handler's own bean graph reaches back into the attempt
 * services, so an eager constructor dependency would close a cycle.</p>
 */
@Service
@Slf4j
public class WorkspaceReleaseOrchestrator {

    private final OrchestrationRunRepository runRepository;
    private final OrchestrationAffinityResolver affinityResolver;
    private final WorkspaceReleaseService releaseService;
    private final ObjectProvider<AgentWebSocketHandler> webSocketHandlerProvider;

    public WorkspaceReleaseOrchestrator(
            OrchestrationRunRepository runRepository,
            OrchestrationAffinityResolver affinityResolver,
            WorkspaceReleaseService releaseService,
            ObjectProvider<AgentWebSocketHandler> webSocketHandlerProvider) {
        this.runRepository = runRepository;
        this.affinityResolver = affinityResolver;
        this.releaseService = releaseService;
        this.webSocketHandlerProvider = webSocketHandlerProvider;
    }

    /**
     * Send the release frame for a request's run when (and only when) the
     * request is orchestrated — pure-inference requests have no run row
     * and no lease.
     *
     * @return true when a release frame was sent
     */
    public boolean releaseIfOrchestrated(WorkflowRequest request) {
        Optional<OrchestrationRun> run = runRepository.findById(request.getId());
        if (run.isEmpty()) {
            return false; // not an orchestrated run
        }
        return release(request.getId());
    }

    /**
     * Send the release frame for one run to its pinned coordinator.
     * Generation 1 in V1 (no generation advance before release).
     */
    public boolean release(UUID runId) {
        UUID coordinator = affinityResolver.coordinatorOf(runId).orElse(null);
        if (coordinator == null) {
            log.info("Run {} has no coordinator — nothing to release", runId);
            return false;
        }
        int generation = runRepository.findById(runId)
                .map(OrchestrationRun::getWorkspaceGeneration)
                .map(g -> g == null ? 1 : g)
                .orElse(1);
        try {
            Map<String, Object> frame = releaseService.releaseFrame(
                    runId, generation, "TERMINAL_STATE");
            AgentWebSocketHandler handler = webSocketHandlerProvider.getIfAvailable();
            if (handler == null) {
                log.info("No websocket handler available for run {} release", runId);
                return false;
            }
            boolean sent = handler.sendOrchestrationRelease(coordinator, frame);
            if (sent) {
                log.info("Sent orchestration.release {} for run {} (generation {}) to {}",
                        frame.get("releaseId"), runId, generation, coordinator);
            } else {
                log.info("Release for run {} not sent (coordinator {} offline) — "
                        + "the expiry sweep reconciles", runId, coordinator);
            }
            return sent;
        } catch (Exception e) {
            log.warn("Release send for run {} failed: {}", runId, e.getMessage());
            return false;
        }
    }
}