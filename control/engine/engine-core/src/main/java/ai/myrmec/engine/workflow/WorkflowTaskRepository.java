package ai.myrmec.engine.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowTaskRepository extends JpaRepository<WorkflowTask, UUID> {

    List<WorkflowTask> findByRequestId(UUID requestId);

    List<WorkflowTask> findByRequestIdAndStatus(UUID requestId, TaskStatus status);

    List<WorkflowTask> findByStatus(TaskStatus status);

    Optional<WorkflowTask> findByRequestIdAndStepIdAndAttempt(UUID requestId, String stepId, Integer attempt);

    boolean existsByRequestIdAndStepId(UUID requestId, String stepId);

    List<WorkflowTask> findByRequestIdAndStepId(UUID requestId, String stepId);

    List<WorkflowTask> findByAgentInstanceId(UUID agentInstanceId);

    List<WorkflowTask> findByApprovalStatus(String approvalStatus);

    /** HITL (§17.4): ORCH_REVIEW tasks whose approval expiry passed —
     * the sweeper applies the terminal APPROVAL_EXPIRED tuple. */
    List<WorkflowTask> findByPauseStateAndApprovalExpiresAtBefore(
            String pauseState, java.time.Instant cutoff);

    /** HITL (§17.4): the decide path runs under the task row lock. */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<WorkflowTask> findWithLockById(UUID id);
}
