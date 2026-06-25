// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.workflow;

import ai.myrmec.engine._system.common.JsonMapConverter;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Service for managing execution-source approvals (DESTRUCTIVE tool calls).
 * 
 * <p>Wave 1 #113 Phase 2: Merge execution-source approvals into unified My Work queue.
 * When a tool is marked DESTRUCTIVE, the WorkflowTask is marked requires_approval=true
 * and enters a PENDING approval state. This service handles the approval workflow:
 * <ul>
 *   <li>Request approval: Set requires_approval flag and populate approval_payload</li>
 *   <li>Approve/Reject: Update approval_status and trigger decision dispatcher</li>
 *   <li>Expiry: Track approval_expires_at for time-bound decisions</li>
 * </ul>
 * 
 * <p>Approval payloads carry tool call details for approvers:
 * {@code {"toolName": "...", "toolDescription": "...", "parameters": {...}}}
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionApprovalService {

    private final WorkflowTaskRepository taskRepository;

    /**
     * Request approval for a workflow task (e.g., DESTRUCTIVE tool call).
     * 
     * @param taskId the workflow task ID
     * @param toolSummary human-readable summary of the tool call (e.g., "Delete customer account")
     * @param payloadMap tool details for approvers (toolName, parameters, etc.)
     * @param expiresAt optional approval expiry; null means no expiry
     * @return the updated WorkflowTask
     */
    @Transactional
    public WorkflowTask requestApproval(UUID taskId, String toolSummary, Map<String, Object> payloadMap, Instant expiresAt) {
        WorkflowTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowTask", taskId.toString()));

        task.setRequiresApproval(true);
        task.setApprovalStatus("PENDING");
        task.setApprovalPayload(payloadMap);
        task.setApprovalRequestedAt(Instant.now());
        task.setApprovalExpiresAt(expiresAt);

        WorkflowTask saved = taskRepository.save(task);
        log.info("Approval requested for task {} ({})", taskId, toolSummary);
        return saved;
    }

    /**
     * Request approval with no explicit expiry.
     * 
     * @param taskId the workflow task ID
     * @param toolSummary human-readable summary of the tool call
     * @param payloadMap tool details for approvers
     * @return the updated WorkflowTask
     */
    @Transactional
    public WorkflowTask requestApproval(UUID taskId, String toolSummary, Map<String, Object> payloadMap) {
        return requestApproval(taskId, toolSummary, payloadMap, null);
    }

    /**
     * Approve a pending task approval.
     * 
     * @param taskId the workflow task ID
     * @return the updated WorkflowTask
     */
    @Transactional
    public WorkflowTask approve(UUID taskId) {
        WorkflowTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowTask", taskId.toString()));

        if (!"PENDING".equals(task.getApprovalStatus())) {
            log.warn("Cannot approve task {} with status {}", taskId, task.getApprovalStatus());
            return task;
        }

        task.setApprovalStatus("APPROVED");
        WorkflowTask saved = taskRepository.save(task);
        log.info("Task {} approved", taskId);
        return saved;
    }

    /**
     * Reject a pending task approval.
     * 
     * @param taskId the workflow task ID
     * @return the updated WorkflowTask
     */
    @Transactional
    public WorkflowTask reject(UUID taskId) {
        WorkflowTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowTask", taskId.toString()));

        if (!"PENDING".equals(task.getApprovalStatus())) {
            log.warn("Cannot reject task {} with status {}", taskId, task.getApprovalStatus());
            return task;
        }

        task.setApprovalStatus("REJECTED");
        WorkflowTask saved = taskRepository.save(task);
        log.info("Task {} rejected", taskId);
        return saved;
    }

    /**
     * Mark a pending approval as expired.
     * 
     * @param taskId the workflow task ID
     * @return the updated WorkflowTask
     */
    @Transactional
    public WorkflowTask expire(UUID taskId) {
        WorkflowTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowTask", taskId.toString()));

        if (!"PENDING".equals(task.getApprovalStatus())) {
            log.warn("Cannot expire task {} with status {}", taskId, task.getApprovalStatus());
            return task;
        }

        task.setApprovalStatus("EXPIRED");
        WorkflowTask saved = taskRepository.save(task);
        log.info("Task {} approval expired", taskId);
        return saved;
    }
}
