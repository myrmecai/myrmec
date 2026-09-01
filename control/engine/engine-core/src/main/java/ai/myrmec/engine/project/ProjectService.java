package ai.myrmec.engine.project;

import ai.myrmec.engine._system.common.DomainConstants.AuditAction;
import ai.myrmec.engine._system.common.AuditReason;
import ai.myrmec.engine._system.common.ResourceType;
import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.audit.AuditEventService;
import ai.myrmec.engine.group.Group;
import ai.myrmec.engine.group.GroupRepository;
import ai.myrmec.engine.project.dto.CreateProjectRequest;
import ai.myrmec.engine.project.dto.UpdateProjectRequest;
import ai.myrmec.engine.secret.SecretResolverService;
import ai.myrmec.engine.service.ServiceType;
import ai.myrmec.engine.user.UserPrincipal;
import ai.myrmec.engine.user.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private final AuditEventService auditEventService;

    /**
     * Find all projects.
     */
    @Transactional(readOnly = true)
    public List<Project> findAll() {
        return projectRepository.findAll();
    }

    /**
     * Find all projects visible to the given principal, mirroring
     * {@code ProjectAccessEvaluator.hasAccess} semantics across the list:
     * <ol>
     *   <li>System-wide grant at VIEWER or above (incl. ORG_ADMIN/PLATFORM_ADMIN)
     *       &rarr; all projects.</li>
     *   <li>Direct project-scoped grant &rarr; that project.</li>
     *   <li>Group-scoped grant &rarr; every project in that group and its descendants.</li>
     * </ol>
     * Closes the RBAC-05 cross-project visibility leak where any authenticated
     * principal could list every project in the org.
     */
    @Transactional(readOnly = true)
    public List<Project> findAllVisibleTo(UserPrincipal user) {
        if (user == null) {
            return List.of();
        }
        // System-wide read access sees everything.
        if (user.hasSystemRole(UserRole.Role.VIEWER)) {
            return projectRepository.findAll();
        }

        Set<UUID> visibleIds = new LinkedHashSet<>(user.projectIdsWithAnyRole());
        for (UUID groupId : user.groupIdsWithAnyRole()) {
            for (UUID gid : groupRepository.findDescendantIds(groupId)) {
                projectRepository.findByGroupId(gid).forEach(p -> visibleIds.add(p.getId()));
            }
        }
        if (visibleIds.isEmpty()) {
            return List.of();
        }
        return projectRepository.findAllById(visibleIds);
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
        // #121 — capture runtime-affecting field values before mutation for
        // the field-level config-change audit event (old/new per changed field).
        Map<String, Object> before = configSnapshot(project);

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
        recordConfigChange(project, before);
        return project;
    }

    // ── #121 field-level project-configuration audit ──────────────────
    // These are the runtime-affecting / governance-relevant fields an auditor
    // cares about ("who flipped this project to STRICT at 14:23").

    /** Snapshot of the audited config fields of a project. */
    private Map<String, Object> configSnapshot(Project p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.getName());
        m.put("status", p.getStatus() != null ? p.getStatus().name() : null);
        m.put("allowedServiceTypes", p.getAllowedServiceTypes() != null
                ? List.copyOf(p.getAllowedServiceTypes()) : null);
        m.put("workspaceRepoUrl", p.getWorkspaceRepoUrl());
        m.put("workspaceRepoBranch", p.getWorkspaceRepoBranch());
        m.put("workspaceCredentialSecretId", p.getWorkspaceCredentialSecretId());
        m.put("autoHitlOnDestructive", p.isAutoHitlOnDestructive());
        m.put("attachmentsEnabled", p.isAttachmentsEnabled());
        m.put("attachmentRetentionTtlDays", p.getAttachmentRetentionTtlDays());
        m.put("attachmentMaxFileSizeBytes", p.getAttachmentMaxFileSizeBytes());
        m.put("attachmentTypeAllowlist", p.getAttachmentTypeAllowlist());
        return m;
    }

    /**
     * Emit a `PROJECT_CONFIG_CHANGED` audit event when any audited field
     * changed. `beforeSnapshot`/`afterSnapshot` carry only the changed fields
     * (field-level old/new), matching the audit model. Never leaks the workspace
     * credential secret — only its id reference.
     */
    private void recordConfigChange(Project after, Map<String, Object> before) {
        Map<String, Object> afterMap = configSnapshot(after);
        Map<String, Object> changedBefore = new LinkedHashMap<>();
        Map<String, Object> changedAfter = new LinkedHashMap<>();
        for (String key : afterMap.keySet()) {
            Object b = before.get(key);
            Object a = afterMap.get(key);
            if (!Objects.equals(b, a)) {
                changedBefore.put(key, b);
                changedAfter.put(key, a);
            }
        }
        if (changedAfter.isEmpty()) {
            return;
        }
        try {
            auditEventService.recordEvent(
                    ResourceType.PROJECT, after.getId(), AuditAction.CONFIG_CHANGED,
                    "PROJECT", after.getId(),
                    null, "SYSTEM",
                    null, AuditReason.CONFIG_CHANGED,
                    changedBefore, changedAfter, null);
        } catch (Exception ex) {
            log.warn("Project config audit failed for {} (continuing): {}", after.getId(), ex.getMessage());
        }
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
