package ai.myrmec.engine.workflow;

import ai.myrmec.engine._system.exception.DuplicateResourceException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectRepository;
import ai.myrmec.engine.user.User;
import ai.myrmec.engine.user.UserRepository;
import ai.myrmec.engine.workflow.dto.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkflowService {

    private final WorkflowRepository workflowRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<WorkflowResponse> findByProject(UUID projectId) {
        return workflowRepository.findByProjectId(projectId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WorkflowResponse> findPublishedByProject(UUID projectId) {
        return workflowRepository.findByProjectIdAndStatus(projectId, WorkflowStatus.PUBLISHED).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkflowResponse findById(UUID id) {
        return workflowRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow", id.toString()));
    }

    @Transactional
    public WorkflowResponse create(CreateWorkflowRequest request, UUID userId) {
        Project project = projectRepository.findById(request.projectId())
                .orElseThrow(() -> new ResourceNotFoundException("Project", request.projectId().toString()));

        if (workflowRepository.existsByProjectIdAndName(request.projectId(), request.name())) {
            throw new DuplicateResourceException("Workflow", "name", request.name());
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId.toString()));

        Workflow workflow = new Workflow();
        workflow.setProject(project);
        workflow.setName(request.name());
        workflow.setDescription(request.description());
        workflow.setSteps(stepsToMapList(request.steps()));
        workflow.setInputSchema(request.inputSchema());
        workflow.setArtifactsRepo(artifactsRepoToMap(request.artifactsRepo()));
        workflow.setVersion(1);
        workflow.setStatus(WorkflowStatus.DRAFT);
        workflow.setCreatedBy(user);

        return toResponse(workflowRepository.save(workflow));
    }

    @Transactional
    public WorkflowResponse update(UUID id, UpdateWorkflowRequest request) {
        Workflow workflow = workflowRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow", id.toString()));

        // Check name uniqueness if changed
        if (!workflow.getName().equals(request.name()) &&
            workflowRepository.existsByProjectIdAndName(workflow.getProject().getId(), request.name())) {
            throw new DuplicateResourceException("Workflow", "name", request.name());
        }

        workflow.setName(request.name());
        workflow.setDescription(request.description());
        workflow.setSteps(stepsToMapList(request.steps()));
        workflow.setInputSchema(request.inputSchema());
        workflow.setArtifactsRepo(artifactsRepoToMap(request.artifactsRepo()));
        
        // Increment version when steps change
        workflow.setVersion(workflow.getVersion() + 1);
        
        if (request.status() != null) {
            workflow.setStatus(request.status());
        }

        return toResponse(workflowRepository.save(workflow));
    }

    @Transactional
    public WorkflowResponse publish(UUID id) {
        Workflow workflow = workflowRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow", id.toString()));

        if (workflow.getStatus() == WorkflowStatus.ARCHIVED) {
            throw new IllegalStateException("Cannot publish an archived workflow");
        }

        workflow.setStatus(WorkflowStatus.PUBLISHED);
        return toResponse(workflowRepository.save(workflow));
    }

    @Transactional
    public WorkflowResponse archive(UUID id) {
        Workflow workflow = workflowRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow", id.toString()));

        workflow.setStatus(WorkflowStatus.ARCHIVED);
        return toResponse(workflowRepository.save(workflow));
    }

    @Transactional
    public void delete(UUID id) {
        Workflow workflow = workflowRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow", id.toString()));

        // TODO: Check if workflow has any requests before deletion

        workflowRepository.delete(workflow);
    }

    private List<Map<String, Object>> stepsToMapList(List<WorkflowStepDto> steps) {
        return objectMapper.convertValue(steps, new TypeReference<>() {});
    }

    private List<WorkflowStepDto> mapListToSteps(List<Map<String, Object>> maps) {
        return objectMapper.convertValue(maps, new TypeReference<>() {});
    }

    private Map<String, Object> artifactsRepoToMap(ArtifactsRepoDto dto) {
        if (dto == null) {
            return null;
        }
        return objectMapper.convertValue(dto, new TypeReference<>() {});
    }

    private ArtifactsRepoDto mapToArtifactsRepo(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        return objectMapper.convertValue(map, ArtifactsRepoDto.class);
    }

    private WorkflowResponse toResponse(Workflow workflow) {
        return new WorkflowResponse(
                workflow.getId(),
                workflow.getProject().getId(),
                workflow.getProject().getName(),
                workflow.getName(),
                workflow.getDescription(),
                mapListToSteps(workflow.getSteps()),
                workflow.getInputSchema(),
                mapToArtifactsRepo(workflow.getArtifactsRepo()),
                workflow.getVersion(),
                workflow.getStatus(),
                workflow.getCreatedBy().getId(),
                workflow.getCreatedBy().getEmail(),
                workflow.getCreatedAt(),
                workflow.getUpdatedAt()
        );
    }
}
