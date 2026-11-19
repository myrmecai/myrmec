// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.inference.execution.SessionExecution;
import ai.myrmec.engine.inference.execution.SessionExecutionRepository;
import ai.myrmec.engine.workflow.dto.WorkflowRequestExecutionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolve a workflow request (run id) to its currently active execution chain.
 * The VS Code plugin only knows the run id; this service discovers the task,
 * current attempt, and in-flight execution needed for §22 interaction calls.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowRequestExecutionService {

    private static final List<SessionExecution.State> IN_FLIGHT_STATES =
            List.of(SessionExecution.State.STARTING, SessionExecution.State.RUNNING,
                    SessionExecution.State.CANCELLING);

    private final WorkflowRequestRepository requestRepository;
    private final WorkflowTaskRepository taskRepository;
    private final TaskAttemptRepository taskAttemptRepository;
    private final SessionRepository sessionRepository;
    private final SessionExecutionRepository executionRepository;

    /**
     * Find the active execution chain for a workflow request, if any.
     *
     * <p>Algorithm: list tasks for the request (oldest-created first), pick the first
     * non-terminal task that has a current attempt, resolve the latest attempt, and map
     * it to an in-flight execution via {@code session_executions.dispatch_id}. A
     * missing/terminal execution means the run has no actionable execution yet/at-all.</p>
     */
    @Transactional(readOnly = true)
    public Optional<WorkflowRequestExecutionResponse> findCurrentExecution(
            UUID teamId, UUID workflowId, UUID requestId) {
        WorkflowRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> ResourceNotFoundException.of("WorkflowRequest", requestId));
        if (request.getWorkflow() == null || !workflowId.equals(request.getWorkflow().getId())) {
            throw ResourceNotFoundException.of("WorkflowRequest", requestId);
        }
        if (request.getWorkflow().getTeam() == null
                || !teamId.equals(request.getWorkflow().getTeam().getId())) {
            throw ResourceNotFoundException.of("WorkflowRequest", requestId);
        }

        List<WorkflowTask> tasks = taskRepository.findByRequestId(requestId);
        for (WorkflowTask task : tasks) {
            if (isTerminal(task.getStatus())) {
                continue;
            }
            TaskAttempt attempt = task.getCurrentAttempt();
            if (attempt == null) {
                attempt = taskAttemptRepository
                        .findFirstByTaskIdOrderByAttemptNumberDesc(task.getId())
                        .orElse(null);
            }
            if (attempt == null) {
                continue;
            }
            SessionExecution execution = executionRepository.findByDispatchId(attempt.getId())
                    .orElse(null);
            if (execution == null || !IN_FLIGHT_STATES.contains(execution.getState())) {
                continue;
            }
            Session session = sessionRepository.findById(execution.getSessionId()).orElse(null);
            if (session == null || !"WORKFLOW".equals(session.getServiceType())
                    || !requestId.equals(session.getRefId())
                    || !teamId.equals(session.getTeamId())) {
                continue;
            }
            return Optional.of(new WorkflowRequestExecutionResponse(
                    teamId,
                    workflowId,
                    requestId,
                    task.getId(),
                    attempt.getId(),
                    execution.getId(),
                    task.getStepId(),
                    task.getStatus(),
                    execution.getState().name(),
                    execution.getHoldState()));
        }
        return Optional.empty();
    }

    private static boolean isTerminal(TaskStatus status) {
        return status == TaskStatus.COMPLETED || status == TaskStatus.CANCELLED;
    }
}
