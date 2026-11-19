package ai.myrmec.engine.team;

import ai.myrmec.engine._system.common.DomainConstants.AuditAction;
import ai.myrmec.engine._system.common.AuditReason;
import ai.myrmec.engine._system.common.ResourceType;
import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.audit.AuditEventService;
import ai.myrmec.engine.group.Group;
import ai.myrmec.engine.group.GroupRepository;
import ai.myrmec.engine.team.dto.CreateTeamRequest;
import ai.myrmec.engine.team.dto.UpdateTeamRequest;
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
 * Service for managing teams.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TeamService {

    private static final int NAME_MAX_LENGTH = 200;
    private static final int DESCRIPTION_MAX_LENGTH = 3000;

    private final TeamRepository teamRepository;
    private final SecretResolverService secretResolver;
    private final GroupRepository groupRepository;
    private final AuditEventService auditEventService;

    /**
     * Find all teams.
     */
    @Transactional(readOnly = true)
    public List<Team> findAll() {
        return teamRepository.findAll();
    }

    /**
     * Find all teams visible to the given principal, mirroring
     * {@code TeamAccessEvaluator.hasAccess} semantics across the list:
     * <ol>
     *   <li>System-wide grant at VIEWER or above (incl. ORG_ADMIN/PLATFORM_ADMIN)
     *       &rarr; all teams.</li>
     *   <li>Direct Team-scoped grant &rarr; that Team.</li>
     *   <li>Group-scoped grant &rarr; every Team in that group and its descendants.</li>
     * </ol>
     * Closes the RBAC-05 cross-Team visibility leak where any authenticated
     * principal could list every Team in the org.
     */
    @Transactional(readOnly = true)
    public List<Team> findAllVisibleTo(UserPrincipal user) {
        if (user == null) {
            return List.of();
        }
        // System-wide read access sees everything.
        if (user.hasSystemRole(UserRole.Role.VIEWER)) {
            return teamRepository.findAll();
        }

        Set<UUID> visibleIds = new LinkedHashSet<>(user.teamIdsWithAnyRole());
        for (UUID groupId : user.groupIdsWithAnyRole()) {
            for (UUID gid : groupRepository.findDescendantIds(groupId)) {
                teamRepository.findByGroupId(gid).forEach(t -> visibleIds.add(t.getId()));
            }
        }
        if (visibleIds.isEmpty()) {
            return List.of();
        }
        return teamRepository.findAllById(visibleIds);
    }

    /**
     * Find Team by ID.
     */
    @Transactional(readOnly = true)
    public Team findById(UUID id) {
        return teamRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("team", "id", id.toString()));
    }

    /**
     * Create a new Team.
     */
    @Transactional
    public Team create(CreateTeamRequest request) {
        validateName(request.getName());
        validateDescription(request.getDescription());

        String trimmedName = request.getName().trim();
        if (teamRepository.existsByName(trimmedName)) {
            throw new BadRequestException("Team with name '" + trimmedName + "' already exists");
        }

        Team team = new Team();
        team.setName(trimmedName);
        team.setDescription(request.getDescription() != null ? request.getDescription().trim() : null);
        team.setStatus(TeamStatus.ACTIVE);

        // Resolve group: explicit groupId must exist; otherwise default to seeded Default group.
        UUID groupId = request.getGroupId() != null ? request.getGroupId() : Group.DEFAULT_GROUP_ID;
        if (!groupRepository.existsById(groupId)) {
            throw ResourceNotFoundException.of("Group", "id", groupId.toString());
        }
        team.setGroupId(groupId);

        // Allowed service types (#77): default to both shipped types when omitted.
        team.setAllowedServiceTypes(
                normaliseServiceTypes(request.getAllowedServiceTypes(),
                        new ArrayList<>(List.of(
                                ServiceType.WORKFLOW.name(), ServiceType.CONVERSATIONAL.name()))));

        // Set workspace repo configuration
        team.setWorkspaceRepoUrl(request.getWorkspaceRepoUrl());
        team.setWorkspaceRepoBranch(request.getWorkspaceRepoBranch() != null ? request.getWorkspaceRepoBranch() : "main");
        // HITL policy default: false. Conscious opt-in by an admin.
        team.setAutoHitlOnDestructive(
                request.getAutoHitlOnDestructive() != null && request.getAutoHitlOnDestructive());
        // #105 Team-level attachment governance defaults to enabled.
        team.setAttachmentsEnabled(
            request.getAttachmentsEnabled() == null || request.getAttachmentsEnabled());
        if (request.getAttachmentRetentionTtlDays() != null
            && request.getAttachmentRetentionTtlDays() <= 0) {
            throw new BadRequestException("attachmentRetentionTtlDays must be > 0");
        }
        if (request.getAttachmentMaxFileSizeBytes() != null
            && request.getAttachmentMaxFileSizeBytes() <= 0) {
            throw new BadRequestException("attachmentMaxFileSizeBytes must be > 0");
        }
        team.setAttachmentRetentionTtlDays(request.getAttachmentRetentionTtlDays());
        team.setAttachmentMaxFileSizeBytes(request.getAttachmentMaxFileSizeBytes());
        team.setAttachmentTypeAllowlist(trimToNull(request.getAttachmentTypeAllowlist()));

        team = teamRepository.save(team);

        if (request.getWorkspaceCredentialSecretId() != null) {
            validateWorkspaceCredentialSecret(team.getId(), request.getWorkspaceCredentialSecretId());
            team.setWorkspaceCredentialSecretId(request.getWorkspaceCredentialSecretId());
            team = teamRepository.save(team);
        }

        log.info("Created team: {} ({})", team.getName(), team.getId());
        return team;
    }

    /**
     * Update an existing Team.
     */
    @Transactional
    public Team update(UUID id, UpdateTeamRequest request) {
        Team team = findById(id);
        // #121 — capture runtime-affecting field values before mutation for
        // the field-level config-change audit event (old/new per changed field).
        Map<String, Object> before = configSnapshot(team);

        if (request.getName() != null) {
            validateName(request.getName());
            String trimmedName = request.getName().trim();
            // Check for duplicate name only if name is changing
            if (!trimmedName.equals(team.getName()) && teamRepository.existsByName(trimmedName)) {
                throw new BadRequestException("Team with name '" + trimmedName + "' already exists");
            }
            team.setName(trimmedName);
        }

        if (request.getDescription() != null) {
            validateDescription(request.getDescription());
            team.setDescription(request.getDescription().trim().isEmpty() ? null : request.getDescription().trim());
        }

        if (request.getStatus() != null) {
            team.setStatus(request.getStatus());
        }

        if (request.getAllowedServiceTypes() != null) {
            team.setAllowedServiceTypes(
                    normaliseServiceTypes(request.getAllowedServiceTypes(), null));
        }

        // Update workspace repo configuration
        if (request.getWorkspaceRepoUrl() != null) {
            team.setWorkspaceRepoUrl(request.getWorkspaceRepoUrl().isEmpty() ? null : request.getWorkspaceRepoUrl());
        }
        if (request.getWorkspaceRepoBranch() != null) {
            team.setWorkspaceRepoBranch(request.getWorkspaceRepoBranch().isEmpty() ? "main" : request.getWorkspaceRepoBranch());
        }
        if (request.getWorkspaceCredentialSecretId() != null) {
            String raw = request.getWorkspaceCredentialSecretId().trim();
            if (raw.isEmpty()) {
                team.setWorkspaceCredentialSecretId(null);
            } else {
                UUID secretId;
                try {
                    secretId = UUID.fromString(raw);
                } catch (IllegalArgumentException e) {
                    throw new BadRequestException("workspaceCredentialSecretId must be a valid UUID");
                }
                validateWorkspaceCredentialSecret(team.getId(), secretId);
                team.setWorkspaceCredentialSecretId(secretId);
            }
        }
        if (request.getAutoHitlOnDestructive() != null) {
            team.setAutoHitlOnDestructive(request.getAutoHitlOnDestructive());
        }
        if (request.getAttachmentsEnabled() != null) {
            team.setAttachmentsEnabled(request.getAttachmentsEnabled());
        }
        if (request.getAttachmentRetentionTtlDays() != null) {
            if (request.getAttachmentRetentionTtlDays() <= 0) {
                throw new BadRequestException("attachmentRetentionTtlDays must be > 0");
            }
            team.setAttachmentRetentionTtlDays(request.getAttachmentRetentionTtlDays());
        }
        if (request.getAttachmentMaxFileSizeBytes() != null) {
            if (request.getAttachmentMaxFileSizeBytes() <= 0) {
                throw new BadRequestException("attachmentMaxFileSizeBytes must be > 0");
            }
            team.setAttachmentMaxFileSizeBytes(request.getAttachmentMaxFileSizeBytes());
        }
        if (request.getAttachmentTypeAllowlist() != null) {
            team.setAttachmentTypeAllowlist(trimToNull(request.getAttachmentTypeAllowlist()));
        }

        team = teamRepository.save(team);
        log.info("Updated team: {} ({})", team.getName(), team.getId());
        recordConfigChange(team, before);
        return team;
    }

    // ── #121 field-level team-configuration audit ──────────────────
    // These are the runtime-affecting / governance-relevant fields an auditor
    // cares about ("who flipped this team to STRICT at 14:23").

    /** Snapshot of the audited config fields of a team. */
    private Map<String, Object> configSnapshot(Team p) {
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
     * Emit a `TEAM_CONFIG_CHANGED` audit event when any audited field
     * changed. `beforeSnapshot`/`afterSnapshot` carry only the changed fields
     * (field-level old/new), matching the audit model. Never leaks the workspace
     * credential secret — only its id reference.
     */
    private void recordConfigChange(Team after, Map<String, Object> before) {
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
                    ResourceType.TEAM, after.getId(), AuditAction.CONFIG_CHANGED,
                    "team", after.getId(),
                    null, "SYSTEM",
                    null, AuditReason.CONFIG_CHANGED,
                    changedBefore, changedAfter, null);
        } catch (Exception ex) {
            log.warn("Team config audit failed for {} (continuing): {}", after.getId(), ex.getMessage());
        }
    }

    /**
     * Move a Team to a different group.
     */
    @Transactional
    public Team moveToGroup(UUID teamId, UUID targetGroupId) {
        if (targetGroupId == null) {
            throw new BadRequestException("Target groupId is required");
        }
        if (!groupRepository.existsById(targetGroupId)) {
            throw ResourceNotFoundException.of("Group", "id", targetGroupId.toString());
        }
        Team team = findById(teamId);
        UUID previous = team.getGroupId();
        team.setGroupId(targetGroupId);
        team = teamRepository.save(team);
        log.info("Moved team {} from group {} to {}", teamId, previous, targetGroupId);
        return team;
    }

    /**
     * Delete a team by ID.
     */
    @Transactional
    public void delete(UUID id) {
        Team team = findById(id);
        // TODO: Check for associated workflows before deletion
        teamRepository.delete(team);
        log.info("Deleted team: {} ({})", team.getName(), id);
    }

    private void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("Team name is required");
        }
        if (name.trim().length() > NAME_MAX_LENGTH) {
            throw new BadRequestException("Team name cannot exceed " + NAME_MAX_LENGTH + " characters");
        }
    }

    private void validateDescription(String description) {
        if (description != null && description.length() > DESCRIPTION_MAX_LENGTH) {
            throw new BadRequestException("Team description cannot exceed " + DESCRIPTION_MAX_LENGTH + " characters");
        }
    }

    /**
     * Validate and canonicalise an {@code allowed_service_types} list (#77).
     * Each entry must name a known {@link ServiceType} (case-insensitive); the
     * result is de-duplicated and stored in canonical upper-case form. A
     * {@code null} input falls back to {@code defaultIfNull}; an explicitly
     * empty or all-unknown list is rejected (a Team must host something).
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
            throw new BadRequestException("A Team must allow at least one service type");
        }
        return canonical;
    }

    /**
     * Ensure the referenced secret exists and is resolvable for the given Team
     * (either Team-scoped to this Team or global).
     */
    private void validateWorkspaceCredentialSecret(UUID teamId, UUID secretId) {
        secretResolver.findResolvable(secretId, teamId)
                .orElseThrow(() -> new BadRequestException(
                        "Workspace credential secret '" + secretId + "' was not found or is not accessible to this Team"));
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
