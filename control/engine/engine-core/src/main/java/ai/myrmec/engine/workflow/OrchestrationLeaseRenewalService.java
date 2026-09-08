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
import java.util.UUID;

/**
 * Engine-owned workspace-lease renewal (design §16.5): "the engine renews
 * live and paused leases". The Host's cleanup is lease-based — a checkout
 * survives only while the engine keeps its run's {@code leaseDeadline} in
 * the future. On expiry the Host grants its own recovery grace, checks the
 * engine's terminal state, and only then deletes.
 *
 * <ul>
 *   <li>An {@code ACTIVE} lease of a non-terminal request renews to
 *       {@code now + leaseRenewalSeconds} every pass.</li>
 *   <li>A paused request's lease extends to the approval deadline plus the
 *       recovery grace (§16.5: "retains its checkout and renews an
 *       engine-owned lease until the approval deadline plus recovery
 *       grace period").</li>
 *   <li>Terminal requests are the release orchestrator's domain — never
 *       renewed here.</li>
 * </ul>
 */
@Service
@Slf4j
public class OrchestrationLeaseRenewalService {

    /** §16.5 live-lease renewal window (engine setting). */
    @Value("${myrmec.orchestration.lease-renewal-seconds:600}")
    private long leaseRenewalSeconds;

    /** §16.5/§17.1 recovery grace added to a paused run's approval deadline. */
    @Value("${myrmec.orchestration.lease-recovery-grace-seconds:3600}")
    private long leaseRecoveryGraceSeconds;

    private final OrchestrationRunRepository runRepository;
    private final WorkflowRequestRepository requestRepository;
    private final WorkflowTaskRepository workflowTaskRepository;

    public OrchestrationLeaseRenewalService(
            OrchestrationRunRepository runRepository,
            WorkflowRequestRepository requestRepository,
            WorkflowTaskRepository workflowTaskRepository) {
        this.runRepository = runRepository;
        this.requestRepository = requestRepository;
        this.workflowTaskRepository = workflowTaskRepository;
    }

    /**
     * Renewal pass — direct invocation in tests; the scheduled timer drives
     * it in production.
     */
    @Scheduled(fixedRateString = "${myrmec.orchestration.lease-renewal.interval-ms:60000}")
    @Transactional
    public void renewLeases() {
        Instant now = Instant.now();
        Instant liveDeadline = now.plus(Duration.ofSeconds(leaseRenewalSeconds));
        Instant graceDeadline = now.plus(Duration.ofSeconds(leaseRecoveryGraceSeconds));

        // Live leases: every run in an ACTIVE/ACQUIRING/SUSPENDED lease
        // whose request is still live (RUNNING/PENDING).
        List<OrchestrationRun> runs = runRepository.findByLeaseStateIn(
                List.of("ACTIVE", "ACQUIRING", "SUSPENDED"));
        for (OrchestrationRun run : runs) {
            WorkflowRequest request = requestRepository.findById(run.getId()).orElse(null);
            if (request == null) {
                continue; // orphan run row — the release path owns it
            }
            RequestStatus status = request.getStatus();
            if (status == RequestStatus.COMPLETED || status == RequestStatus.FAILED
                    || status == RequestStatus.CANCELLED) {
                continue; // terminal: the release orchestrator's domain
            }
            if (status == RequestStatus.PAUSED) {
                // §16.5: approval deadline + recovery grace. A paused run
                // without an approval deadline keeps the grace window.
                Instant approvalDeadline = taskApprovalDeadline(run.getId());
                Instant pausedDeadline = approvalDeadline != null
                        ? approvalDeadline.plus(Duration.ofSeconds(leaseRecoveryGraceSeconds))
                        : graceDeadline;
                if (run.getLeaseDeadline() == null
                        || run.getLeaseDeadline().isBefore(pausedDeadline)) {
                    run.setLeaseState("SUSPENDED");
                    run.setLeaseDeadline(pausedDeadline);
                    runRepository.save(run);
                    log.debug("Run {} paused-lease renewed to {}", run.getId(), pausedDeadline);
                }
                continue;
            }
            // RUNNING/PENDING: live renewal.
            run.setLeaseState("ACTIVE");
            run.setLeaseDeadline(liveDeadline);
            runRepository.save(run);
        }
    }

    /** The earliest pending-approval deadline among the run's tasks. */
    private Instant taskApprovalDeadline(UUID runId) {
        return workflowTaskRepository.findByRequestId(runId).stream()
                .map(WorkflowTask::getApprovalExpiresAt)
                .filter(java.util.Objects::nonNull)
                .min(Instant::compareTo)
                .orElse(null);
    }
}