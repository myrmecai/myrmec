// SPDX-License-Identifier: Apache-2.0
// Copyright 2025 The Myrmec Authors

package ai.myrmec.engine.workflow;

import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Phase 3 — Routes HITL approval decisions for execution-source approvals
 * (WorkflowTask) to the ExecutionApprovalService.
 *
 * <p>When a human approves or rejects an approval request that originated
 * from a DESTRUCTIVE tool execution, the decision is routed here to
 * transition the task state appropriately.
 *
 * <p>Unlike ConversationApprovalDispatcher which sends a frame to the
 * agent WebSocket, this dispatcher applies the decision directly to the
 * task (approve = mark APPROVED, reject = mark REJECTED, expire = mark EXPIRED).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ExecutionApprovalDecisionDispatcher {

    private final ExecutionApprovalService executionApprovalService;
    private final WorkflowTaskRepository workflowTaskRepository;

    /**
     * Route an approval decision for an execution-source approval.
     *
     * @param taskId The ID of the WorkflowTask awaiting approval
     * @param decision The decision: "APPROVED", "REJECTED", or "EXPIRED"
     * @throws ResourceNotFoundException if task not found
     * @throws IllegalArgumentException if decision is invalid
     */
    public void dispatch(UUID taskId, String decision) {
        if (decision == null || decision.isBlank()) {
            throw new IllegalArgumentException("Decision cannot be null or blank");
        }

        // Verify task exists
        WorkflowTask task = workflowTaskRepository.findById(taskId)
                .orElseThrow(() -> ResourceNotFoundException.of("WorkflowTask", "id", taskId));

        // Route based on decision
        switch (decision.toUpperCase()) {
            case "APPROVED" -> {
                executionApprovalService.approve(taskId);
                log.info("Approved execution approval for task {}", taskId);
            }
            case "REJECTED" -> {
                executionApprovalService.reject(taskId);
                log.info("Rejected execution approval for task {}", taskId);
            }
            case "EXPIRED" -> {
                executionApprovalService.expire(taskId);
                log.info("Expired execution approval for task {}", taskId);
            }
            default -> throw new IllegalArgumentException("Invalid decision: " + decision);
        }
    }
}
