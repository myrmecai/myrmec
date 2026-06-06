package ai.myrmec.engine.project;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.project.dto.AssignProjectMemberRequest;
import ai.myrmec.engine.project.dto.ProjectMemberCandidate;
import ai.myrmec.engine.project.dto.ProjectMemberListResponse;
import ai.myrmec.engine.project.dto.ProjectMemberResponse;
import ai.myrmec.engine.project.dto.SystemWideUserSummary;
import ai.myrmec.engine.user.User;
import ai.myrmec.engine.user.UserRepository;
import ai.myrmec.engine.user.UserRole;
import ai.myrmec.engine.user.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Manages project-scoped user memberships backed by {@code user_roles} rows
 * where {@code project_id} is set.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectMemberService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;

    @Transactional(readOnly = true)
    public ProjectMemberListResponse list(UUID projectId) {
        ensureProjectExists(projectId);

        List<UserRole> projectRoles = userRoleRepository.findByProjectId(projectId);
        // "Global users" panel: any user with a system-wide role who is NOT
        // yet a member of this project. Only data-access roles (EDITOR/VIEWER)
        // and platform/org admins are surfaced; functional roles like
        // BUDGET_OWNER/APPROVER/AUDITOR are not part of the project member UX.
        Set<UUID> projectMemberUserIds = projectRoles.stream()
                .map(UserRole::getUserId)
                .collect(Collectors.toSet());
        List<UserRole> systemRoles = userRoleRepository.findByProjectIdIsNull().stream()
                .filter(r -> !projectMemberUserIds.contains(r.getUserId()))
                .toList();

        Set<UUID> referencedUserIds = new HashSet<>();
        projectRoles.forEach(r -> referencedUserIds.add(r.getUserId()));
        systemRoles.forEach(r -> referencedUserIds.add(r.getUserId()));
        projectRoles.forEach(r -> {
            if (r.getGrantedByUserId() != null) {
                referencedUserIds.add(r.getGrantedByUserId());
            }
        });

        Map<UUID, User> usersById = userRepository.findAllById(referencedUserIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        List<ProjectMemberResponse> members = projectRoles.stream()
                .map(r -> toMemberResponse(r, usersById))
                .filter(m -> m != null)
                .sorted(Comparator.comparing(ProjectMemberResponse::getEmail,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        // Pick highest data-access system role per user. Roles outside the
        // data-access axis (PLATFORM_ADMIN/ORG_ADMIN/BUDGET_OWNER/APPROVER/
        // AUDITOR) are not comparable via isAtLeast and are filtered out here.
        Map<UUID, UserRole.Role> highestSystemRole = systemRoles.stream()
                .filter(r -> r.getRole().dataAccessRank() > 0)
                .collect(Collectors.toMap(
                        UserRole::getUserId,
                        UserRole::getRole,
                        (a, b) -> a.isAtLeast(b) ? a : b));

        List<SystemWideUserSummary> systemWide = highestSystemRole.entrySet().stream()
                .map(e -> {
                    User u = usersById.get(e.getKey());
                    if (u == null || Boolean.FALSE.equals(u.getIsActive())) {
                        return null;
                    }
                    return SystemWideUserSummary.builder()
                            .userId(u.getId())
                            .email(u.getEmail())
                            .name(u.getName())
                            .role(e.getValue())
                            .build();
                })
                .filter(s -> s != null)
                .sorted(Comparator.comparing(SystemWideUserSummary::getEmail,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        return ProjectMemberListResponse.builder()
                .projectMembers(members)
                .systemWideUsers(systemWide)
                .build();
    }

    /**
     * Active users not yet assigned an explicit project role on this project,
     * shown in the "add member" picker.
     */
    @Transactional(readOnly = true)
    public List<ProjectMemberCandidate> listCandidates(UUID projectId) {
        ensureProjectExists(projectId);

        Set<UUID> alreadyMembers = userRoleRepository.findByProjectId(projectId).stream()
                .map(UserRole::getUserId)
                .collect(Collectors.toSet());

        // PLATFORM_ADMIN / ORG_ADMIN have implicit access to every project — hide them.
        // Global EDITOR/VIEWER users remain eligible for project-scoped assignment.
        Set<UUID> systemAdminUserIds = new HashSet<>();
        userRoleRepository.findByRoleAndProjectIdIsNull(UserRole.Role.PLATFORM_ADMIN)
                .forEach(r -> systemAdminUserIds.add(r.getUserId()));
        userRoleRepository.findByRoleAndProjectIdIsNull(UserRole.Role.ORG_ADMIN)
                .forEach(r -> systemAdminUserIds.add(r.getUserId()));

        return userRepository.findByIsActiveTrueOrderByEmailAsc().stream()
                .filter(u -> !alreadyMembers.contains(u.getId()))
                .filter(u -> !systemAdminUserIds.contains(u.getId()))
                .map(u -> ProjectMemberCandidate.builder()
                        .userId(u.getId())
                        .email(u.getEmail())
                        .name(u.getName())
                        .build())
                .toList();
    }

    /**
     * Grant an additional project-scoped role to a user. A user may hold multiple
     * roles on the same project (e.g. EDITOR + APPROVER); each role is a separate
     * {@code user_roles} row. Granting a role the user already holds on this project
     * is a no-op and returns the existing row.
     */
    @Transactional
    public ProjectMemberResponse assign(UUID projectId, AssignProjectMemberRequest request, UUID grantedByUserId) {
        ensureProjectExists(projectId);

        if (request.getRole() == UserRole.Role.PLATFORM_ADMIN
                || request.getRole() == UserRole.Role.ORG_ADMIN) {
            throw new BadRequestException(request.getRole() + " is system-wide and cannot be assigned at the project scope");
        }

        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> ResourceNotFoundException.of("User", request.getUserId()));
        if (Boolean.FALSE.equals(user.getIsActive())) {
            throw new BadRequestException("User is inactive and cannot be assigned to a project");
        }

        Map<UUID, User> ctx = Map.of(user.getId(), user);
        User grantedByEarly = grantedByUserId != null ? userRepository.findById(grantedByUserId).orElse(null) : null;

        Optional<UserRole> duplicate = userRoleRepository.findByUserIdAndProjectId(user.getId(), projectId).stream()
                .filter(r -> r.getRole() == request.getRole())
                .findFirst();
        if (duplicate.isPresent()) {
            return toMemberResponse(duplicate.get(), ctx, grantedByEarly);
        }

        UserRole row = new UserRole();
        row.setUserId(user.getId());
        row.setRole(request.getRole());
        row.setScopeType(UserRole.ScopeType.PROJECT);
        row.setProjectId(projectId);
        row.setGrantedByUserId(grantedByUserId);
        row = userRoleRepository.save(row);

        log.info("Assigned user {} as {} on project {} (granted by {})",
                user.getId(), request.getRole(), projectId, grantedByUserId);

        return toMemberResponse(row, ctx, grantedByEarly);
    }

    @Transactional
    public void remove(UUID projectId, UUID userId, UUID roleId) {
        ensureProjectExists(projectId);

        UserRole row = userRoleRepository.findById(roleId)
                .orElseThrow(() -> ResourceNotFoundException.of("ProjectMemberRole", roleId));

        if (!projectId.equals(row.getProjectId()) || !userId.equals(row.getUserId())) {
            throw ResourceNotFoundException.of("ProjectMemberRole", roleId);
        }
        if (row.getProjectId() == null) {
            // Defensive: this endpoint only manages project-scoped rows.
            throw new BadRequestException("Cannot remove a system-wide role from a project page");
        }

        userRoleRepository.delete(row);
        log.info("Removed user {} from project {} (role row {})", userId, projectId, roleId);
    }

    private void ensureProjectExists(UUID projectId) {
        if (!projectRepository.existsById(projectId)) {
            throw ResourceNotFoundException.of("Project", projectId);
        }
    }

    private ProjectMemberResponse toMemberResponse(UserRole row, Map<UUID, User> usersById) {
        return toMemberResponse(row, usersById, Optional.ofNullable(row.getGrantedByUserId())
                .map(usersById::get).orElse(null));
    }

    private ProjectMemberResponse toMemberResponse(UserRole row, Map<UUID, User> usersById, User grantedBy) {
        User user = usersById.get(row.getUserId());
        if (user == null) {
            // Orphaned role row (user deleted but FK missing) — skip in the listing.
            return null;
        }
        return ProjectMemberResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .isActive(user.getIsActive())
                .role(row.getRole())
                .projectRoleId(row.getId())
                .grantedByUserId(row.getGrantedByUserId())
                .grantedByEmail(grantedBy != null ? grantedBy.getEmail() : null)
                .createdAt(row.getCreatedAt())
                .build();
    }
}
