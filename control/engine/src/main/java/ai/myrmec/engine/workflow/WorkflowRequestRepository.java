package ai.myrmec.engine.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkflowRequestRepository extends JpaRepository<WorkflowRequest, UUID> {

    List<WorkflowRequest> findByWorkflowId(UUID workflowId);

    List<WorkflowRequest> findByWorkflowIdAndStatus(UUID workflowId, RequestStatus status);

    List<WorkflowRequest> findByWorkflowProjectId(UUID projectId);

    List<WorkflowRequest> findByCreatedById(UUID userId);
}
