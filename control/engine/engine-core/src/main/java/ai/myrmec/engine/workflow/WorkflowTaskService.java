package ai.myrmec.engine.workflow;

import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.workflow.dto.WorkflowTaskResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkflowTaskService {

    private final WorkflowTaskRepository taskRepository;

    @Transactional(readOnly = true)
    public List<WorkflowTaskResponse> findByRequest(UUID requestId) {
        return taskRepository.findByRequestId(requestId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkflowTaskResponse findById(UUID id) {
        return taskRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowTask", id.toString()));
    }

    @Transactional(readOnly = true)
    public List<WorkflowTaskResponse> findByAgentInstance(UUID agentInstanceId) {
        return taskRepository.findByAgentInstanceId(agentInstanceId).stream()
                .map(this::toResponse)
                .toList();
    }

    private WorkflowTaskResponse toResponse(WorkflowTask task) {
        return new WorkflowTaskResponse(
                task.getId(),
                task.getRequest().getId(),
                task.getStepId(),
                task.getAgentProfile().getId(),
                task.getAgentProfile().getName(),
                task.getAgentInstance() != null ? task.getAgentInstance().getId() : null,
                task.getInput(),
                task.getOutput(),
                task.getStatus(),
                task.getResult(),
                task.getErrorMessage(),
                task.getAttempt(),
                task.getCreatedAt(),
                task.getStartedAt(),
                task.getCompletedAt(),
                task.getMetrics()
        );
    }
}
