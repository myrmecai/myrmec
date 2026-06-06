package ai.myrmec.engine.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
