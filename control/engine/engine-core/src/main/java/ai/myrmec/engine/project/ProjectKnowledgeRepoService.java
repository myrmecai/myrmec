package ai.myrmec.engine.project;

import ai.myrmec.engine._system.exception.DuplicateResourceException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.project.dto.ProjectKnowledgeRepoRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * CRUD service for project knowledge repositories.
 *
 * Knowledge repo entries are pure configuration: contents are fetched on-demand
 * by {@code KnowledgeRepoFetcher} at task dispatch time and are never persisted
 * into {@code knowledge_documents}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectKnowledgeRepoService {

    private final ProjectKnowledgeRepoRepository repository;
    private final ProjectRepository projectRepository;

    @Transactional(readOnly = true)
    public List<ProjectKnowledgeRepo> list(UUID projectId) {
        validateProjectExists(projectId);
        return repository.findByProjectIdOrderByNameAsc(projectId);
    }

    @Transactional(readOnly = true)
    public ProjectKnowledgeRepo get(UUID projectId, UUID id) {
        return repository.findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> ResourceNotFoundException.of("ProjectKnowledgeRepo", "id", id.toString()));
    }

    @Transactional
    public ProjectKnowledgeRepo create(UUID projectId, ProjectKnowledgeRepoRequest request) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> ResourceNotFoundException.project(projectId));

        String name = request.name().trim();
        if (repository.existsByProjectIdAndName(projectId, name)) {
            throw new DuplicateResourceException("ProjectKnowledgeRepo", "name", name);
        }

        ProjectKnowledgeRepo entry = new ProjectKnowledgeRepo();
        entry.setProject(project);
        apply(entry, request, name);

        ProjectKnowledgeRepo saved = repository.save(entry);
        log.info("Created knowledge repo {} ({}) for project {}", saved.getName(), saved.getId(), projectId);
        return saved;
    }

    @Transactional
    public ProjectKnowledgeRepo update(UUID projectId, UUID id, ProjectKnowledgeRepoRequest request) {
        ProjectKnowledgeRepo entry = get(projectId, id);

        String name = request.name().trim();
        if (!entry.getName().equals(name) && repository.existsByProjectIdAndNameAndIdNot(projectId, name, id)) {
            throw new DuplicateResourceException("ProjectKnowledgeRepo", "name", name);
        }

        apply(entry, request, name);
        ProjectKnowledgeRepo saved = repository.save(entry);
        log.info("Updated knowledge repo {} ({}) for project {}", saved.getName(), saved.getId(), projectId);
        return saved;
    }

    @Transactional
    public void delete(UUID projectId, UUID id) {
        ProjectKnowledgeRepo entry = get(projectId, id);
        repository.delete(entry);
        log.info("Deleted knowledge repo {} ({}) for project {}", entry.getName(), id, projectId);
    }

    private void apply(ProjectKnowledgeRepo entry, ProjectKnowledgeRepoRequest request, String trimmedName) {
        entry.setName(trimmedName);
        entry.setRepoUrl(request.repoUrl().trim());
        String branch = request.branch();
        entry.setBranch(branch == null || branch.isBlank() ? "main" : branch.trim());
        entry.setInstructionPaths(request.instructionPaths());
        entry.setCredentialSecretId(request.credentialSecretId());
    }

    private void validateProjectExists(UUID projectId) {
        if (!projectRepository.existsById(projectId)) {
            throw ResourceNotFoundException.project(projectId);
        }
    }
}
