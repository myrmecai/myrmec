package ai.myrmec.engine.project;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.group.Group;
import ai.myrmec.engine.group.GroupRepository;
import ai.myrmec.engine.project.dto.CreateProjectRequest;
import ai.myrmec.engine.project.dto.UpdateProjectRequest;
import ai.myrmec.engine.secret.SecretResolverService;
import ai.myrmec.engine.service.ServiceType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service for managing projects.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectService {

    private static final int NAME_MAX_LENGTH = 200;
    private static final int DESCRIPTION_MAX_LENGTH = 3000;

    private final ProjectRepository projectRepository;
    private final SecretResolverService secretResolver;
    private final GroupRepository groupRepository;

    /**
     * Find all projects.
     */
    @Transactional(readOnly = true)
    public List<Project> findAll() {
        return projectRepository.findAll();
    }

    /**
     * Find project by ID.
     */
    @Transactional(readOnly = true)
    public Project findById(UUID id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Project", "id", id.toString()));
    }

    /**
     * Create a new project.
     */
    @Transactional
    public Project create(CreateProjectRequest request) {
        validateName(request.getName());
        validateDescription(request.getDescription());

        String trimmedName = request.getName().trim();
        if (projectRepository.existsByName(trimmedName)) {
            throw new BadRequestException("Project with name '" + trimmedName + "' already exists");
        }

        Project project = new Project();
        project.setName(trimmedName);
        project.setDescription(request.getDescription() != null ? request.getDescription().trim() : null);
        project.setStatus(ProjectStatus.ACTIVE);

        // Resolve group: explicit groupId must exist; otherwise default to seeded Default group.
        UUID groupId = request.getGroupId() != null ? request.getGroupId() : Group.DEFAULT_GROUP_ID;
        if (!groupRepository.existsById(groupId)) {
            throw ResourceNotFoundException.of("Group", "id", groupId.toString());
        }
        project.setGroupId(groupId);

        // Allowed service types (#77): default to both shipped types when omitted.
        project.setAllowedServiceTypes(
                normaliseServiceTypes(request.getAllowedServiceTypes(),
                        new ArrayList<>(List.of(
                                ServiceType.WORKFLOW.name(), ServiceType.CONVERSATIONAL.name()))));

        // Set workspace repo configuration
        project.setWorkspaceRepoUrl(request.getWorkspaceRepoUrl());
        project.setWorkspaceRepoBranch(request.getWorkspaceRepoBranch() != null ? request.getWorkspaceRepoBranch() : "main");
        project.setRagConfig(request.getRagConfig());
        // HITL policy default: false. Conscious opt-in by an admin.
        project.setAutoHitlOnDestructive(
                request.getAutoHitlOnDestructive() != null && request.getAutoHitlOnDestructive());
        // #105 project-level attachment governance defaults to enabled.
        project.setAttachmentsEnabled(
            request.getAttachmentsEnabled() == null || request.getAttachmentsEnabled());
        if (request.getAttachmentRetentionTtlDays() != null
            && request.getAttachmentRetentionTtlDays() <= 0) {
            throw new BadRequestException("attachmentRetentionTtlDays must be > 0");
        }
        if (request.getAttachmentMaxFileSizeBytes() != null
            && request.getAttachmentMaxFileSizeBytes() <= 0) {
            throw new BadRequestException("attachmentMaxFileSizeBytes must be > 0");
        }
        project.setAttachmentRetentionTtlDays(request.getAttachmentRetentionTtlDays());
        project.setAttachmentMaxFileSizeBytes(request.getAttachmentMaxFileSizeBytes());
        project.setAttachmentTypeAllowlist(trimToNull(request.getAttachmentTypeAllowlist()));

        project = projectRepository.save(project);

        if (request.getWorkspaceCredentialSecretId() != null) {
            validateWorkspaceCredentialSecret(project.getId(), request.getWorkspaceCredentialSecretId());
            project.setWorkspaceCredentialSecretId(request.getWorkspaceCredentialSecretId());
            project = projectRepository.save(project);
        }

        log.info("Created project: {} ({})", project.getName(), project.getId());
        return project;
    }

    /**
     * Update an existing project.
     */
    @Transactional
    public Project update(UUID id, UpdateProjectRequest request) {
        Project project = findById(id);

        if (request.getName() != null) {
            validateName(request.getName());
            String trimmedName = request.getName().trim();
            // Check for duplicate name only if name is changing
            if (!trimmedName.equals(project.getName()) && projectRepository.existsByName(trimmedName)) {
                throw new BadRequestException("Project with name '" + trimmedName + "' already exists");
            }
            project.setName(trimmedName);
        }

        if (request.getDescription() != null) {
            validateDescription(request.getDescription());
            project.setDescription(request.getDescription().trim().isEmpty() ? null : request.getDescription().trim());
        }

        if (request.getStatus() != null) {
            project.setStatus(request.getStatus());
        }

        if (request.getAllowedServiceTypes() != null) {
            project.setAllowedServiceTypes(
                    normaliseServiceTypes(request.getAllowedServiceTypes(), null));
        }

        // Update workspace repo configuration
        if (request.getWorkspaceRepoUrl() != null) {
            project.setWorkspaceRepoUrl(request.getWorkspaceRepoUrl().isEmpty() ? null : request.getWorkspaceRepoUrl());
        }
        if (request.getWorkspaceRepoBranch() != null) {
            project.setWorkspaceRepoBranch(request.getWorkspaceRepoBranch().isEmpty() ? "main" : request.getWorkspaceRepoBranch());
        }
        if (request.getWorkspaceCredentialSecretId() != null) {
            String raw = request.getWorkspaceCredentialSecretId().trim();
            if (raw.isEmpty()) {
                project.setWorkspaceCredentialSecretId(null);
            } else {
                UUID secretId;
                try {
                    secretId = UUID.fromString(raw);
                } catch (IllegalArgumentException e) {
                    throw new BadRequestException("workspaceCredentialSecretId must be a valid UUID");
                }
                validateWorkspaceCredentialSecret(project.getId(), secretId);
                project.setWorkspaceCredentialSecretId(secretId);
            }
        }
        if (request.getRagConfig() != null) {
            project.setRagConfig(request.getRagConfig());
        }
        if (request.getAutoHitlOnDestructive() != null) {
            project.setAutoHitlOnDestructive(request.getAutoHitlOnDestructive());
        }
        if (request.getAttachmentsEnabled() != null) {
            project.setAttachmentsEnabled(request.getAttachmentsEnabled());
        }
        if (request.getAttachmentRetentionTtlDays() != null) {
            if (request.getAttachmentRetentionTtlDays() <= 0) {
                throw new BadRequestException("attachmentRetentionTtlDays must be > 0");
            }
            project.setAttachmentRetentionTtlDays(request.getAttachmentRetentionTtlDays());
        }
        if (request.getAttachmentMaxFileSizeBytes() != null) {
            if (request.getAttachmentMaxFileSizeBytes() <= 0) {
                throw new BadRequestException("attachmentMaxFileSizeBytes must be > 0");
            }
            project.setAttachmentMaxFileSizeBytes(request.getAttachmentMaxFileSizeBytes());
        }
        if (request.getAttachmentTypeAllowlist() != null) {
            project.setAttachmentTypeAllowlist(trimToNull(request.getAttachmentTypeAllowlist()));
        }

        project = projectRepository.save(project);
        log.info("Updated project: {} ({})", project.getName(), project.getId());
        return project;
    }

    /**
     * Move a project to a different group.
     */
    @Transactional
    public Project moveToGroup(UUID projectId, UUID targetGroupId) {
        if (targetGroupId == null) {
            throw new BadRequestException("Target groupId is required");
        }
        if (!groupRepository.existsById(targetGroupId)) {
            throw ResourceNotFoundException.of("Group", "id", targetGroupId.toString());
        }
        Project project = findById(projectId);
        UUID previous = project.getGroupId();
        project.setGroupId(targetGroupId);
        project = projectRepository.save(project);
        log.info("Moved project {} from group {} to {}", projectId, previous, targetGroupId);
        return project;
    }

    /**
     * Delete a project by ID.
     */
    @Transactional
    public void delete(UUID id) {
        Project project = findById(id);
        // TODO: Check for associated workflows before deletion
        projectRepository.delete(project);
        log.info("Deleted project: {} ({})", project.getName(), id);
    }

    private void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("Project name is required");
        }
        if (name.trim().length() > NAME_MAX_LENGTH) {
            throw new BadRequestException("Project name cannot exceed " + NAME_MAX_LENGTH + " characters");
        }
    }

    private void validateDescription(String description) {
        if (description != null && description.length() > DESCRIPTION_MAX_LENGTH) {
            throw new BadRequestException("Project description cannot exceed " + DESCRIPTION_MAX_LENGTH + " characters");
        }
    }

    /**
     * Validate and canonicalise an {@code allowed_service_types} list (#77).
     * Each entry must name a known {@link ServiceType} (case-insensitive); the
     * result is de-duplicated and stored in canonical upper-case form. A
     * {@code null} input falls back to {@code defaultIfNull}; an explicitly
     * empty or all-unknown list is rejected (a project must host something).
     */
    private List<String> normaliseServiceTypes(List<String> requested, List<String> defaultIfNull) {
        if (requested == null) {
            return defaultIfNull;
        }
        List<String> canonical = new ArrayList<>();
        for (String raw : requested) {
            ServiceType type = ServiceType.fromString(raw)
                    .orElseThrow(() -> new BadRequestException(
                            "Unknown service type '" + raw + "'"));
            if (!canonical.contains(type.name())) {
                canonical.add(type.name());
            }
        }
        if (canonical.isEmpty()) {
            throw new BadRequestException("A project must allow at least one service type");
        }
        return canonical;
    }

    /**
     * Ensure the referenced secret exists and is resolvable for the given project
     * (either project-scoped to this project or global).
     */
    private void validateWorkspaceCredentialSecret(UUID projectId, UUID secretId) {
        secretResolver.findResolvable(secretId, projectId)
                .orElseThrow(() -> new BadRequestException(
                        "Workspace credential secret '" + secretId + "' was not found or is not accessible to this project"));
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
