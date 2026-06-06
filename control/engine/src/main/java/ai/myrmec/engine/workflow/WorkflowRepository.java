package ai.myrmec.engine.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowRepository extends JpaRepository<Workflow, UUID> {

    List<Workflow> findByProjectId(UUID projectId);

    List<Workflow> findByProjectIdAndStatus(UUID projectId, WorkflowStatus status);

    Optional<Workflow> findByProjectIdAndName(UUID projectId, String name);

    boolean existsByProjectIdAndName(UUID projectId, String name);
}
