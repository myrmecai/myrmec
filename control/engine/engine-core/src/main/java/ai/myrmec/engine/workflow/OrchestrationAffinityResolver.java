// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * V1 agent affinity for orchestration runs (design §16.4). The first task
 * selects a compatible Agent; its attempt records the coordinator. Later
 * orchestration tasks and retries in the same request wait for that exact
 * instance instead of choosing another. Availability throttling is durable
 * under the locked run row: episodes increment on available→unavailable
 * transitions, occurrences per eligible throttled pass, deterministic
 * scheduling events, bounded exponential backoff. The first unexpected
 * loss starts a pinned {@code affinityRecoveryDeadline} (15-minute default)
 * that retries and heartbeats never extend; reconnection with the same
 * Host/generation proof clears the condition; expiry or explicit loss is
 * terminal {@code WORKSPACE_LOST}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrchestrationAffinityResolver {

    /** Engine setting §16.4; default 15 minutes. */
    @Value("${myrmec.orchestration.affinity-recovery-timeout:900}")
    private long affinityRecoveryTimeoutSeconds;

    private final OrchestrationRunRepository runRepository;

    /** The run's coordinator instance, if one was selected. */
    @Transactional(readOnly = true)
    public Optional<UUID> coordinatorOf(UUID runId) {
        return runRepository.findById(runId)
                .map(OrchestrationRun::getCoordinatorAgentId);
    }

    /**
     * Record the coordinator selection from the first dispatched attempt.
     * Idempotent — the first selection wins (a concurrent second dispatch
     * under the run lock sees the recorded value).
     */
    @Transactional
    public UUID recordCoordinator(UUID runId, UUID agentInstanceId, UUID hostId) {
        OrchestrationRun run = runRepository.findWithLockById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown run: " + runId));
        if (run.getCoordinatorAgentId() == null) {
            run.setCoordinatorAgentId(agentInstanceId);
            run.setCoordinatorHostId(hostId);
            log.info("Run {} coordinator pinned to agent {} (host {})",
                    runId, agentInstanceId, hostId);
        }
        return run.getCoordinatorAgentId();
    }

    /**
     * Record one unavailable observation for the coordinator. Under the run
     * row lock: the first unavailable transition after AVAILABLE increments
     * {@code availabilityEpisode}, resets {@code occurrence}, and starts the
     * pinned recovery deadline (only if not already started — it is never
     * extended). Returns the deterministic scheduling event id for the
     * throttle pass.
     */
    @Transactional
    public UnavailableObservation observeUnavailable(UUID runId, UUID taskId, Instant now) {
        OrchestrationRun run = runRepository.findWithLockById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown run: " + runId));

        if ("UNAVAILABLE".equals(run.getAvailabilityState())) {
            // Same episode: bump occurrence only for an eligible pass (the
            // caller applies nextEligibleAt gating before calling).
            run.setAvailabilityOccurrence(run.getAvailabilityOccurrence() + 1);
        } else {
            run.setAvailabilityState("UNAVAILABLE");
            run.setAvailabilityEpisode(run.getAvailabilityEpisode() + 1);
            run.setAvailabilityOccurrence(0);
        }

        // §16.4 (5): the deadline starts at the FIRST unexpected loss and is
        // never extended by retries or other Agents' heartbeats.
        if (run.getAffinityRecoveryDeadline() == null) {
            run.setAffinityRecoveryDeadline(
                    now.plus(Duration.ofSeconds(affinityRecoveryTimeoutSeconds)));
            log.warn("Run {} coordinator lost — recovery deadline {} started",
                    runId, run.getAffinityRecoveryDeadline());
        }

        UUID schedulingEventId = OrchestrationIds.schedulingEventId(
                runId, taskId, run.getAvailabilityEpisode(), run.getAvailabilityOccurrence());
        return new UnavailableObservation(
                schedulingEventId,
                run.getAvailabilityEpisode(),
                run.getAvailabilityOccurrence(),
                run.getAffinityRecoveryDeadline());
    }

    /**
     * The coordinator reconnected with same-Host/generation proof before the
     * deadline: clear the availability condition (a new outage starts a new
     * episode). The reconnect proof is the established authenticated
     * WebSocket session itself — same instance identity, same Host
     * registration the dispatcher pins against.
     */
    @Transactional
    public void observeAvailable(UUID runId) {
        OrchestrationRun run = runRepository.findWithLockById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown run: " + runId));
        run.setAvailabilityState("AVAILABLE");
        log.info("Run {} coordinator reconnected — availability restored", runId);
    }

    /**
     * §16.4 (7): clear the availability condition of every run coordinated
     * by the reconnecting instance (reconnect proof = the authenticated
     * session's instance identity). AVAILABLE/unknown runs are no-ops.
     */
    @Transactional
    public void observeAvailableForCoordinator(UUID coordinatorAgentId) {
        for (OrchestrationRun run : runRepository.findByCoordinatorAgentId(coordinatorAgentId)) {
            if ("UNAVAILABLE".equals(run.getAvailabilityState())) {
                observeAvailable(run.getId());
            }
        }
    }

    /**
     * Whether the recovery deadline has expired without reconnection proof.
     * Terminal {@code WORKSPACE_LOST} is applied by the outcome path using
     * an engine-generated result (the Agent is gone; no runner result will
     * arrive).
     */
    @Transactional(readOnly = true)
    public boolean isRecoveryExpired(UUID runId, Instant now) {
        return runRepository.findById(runId)
                .map(OrchestrationRun::getAffinityRecoveryDeadline)
                .map(deadline -> now.isAfter(deadline))
                .orElse(false);
    }

    /** One throttled unavailable observation + its deterministic event id. */
    public record UnavailableObservation(
            UUID schedulingEventId,
            int episode,
            int occurrence,
            Instant recoveryDeadline) {}
}