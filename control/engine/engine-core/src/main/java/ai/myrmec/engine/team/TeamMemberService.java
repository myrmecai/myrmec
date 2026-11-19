package ai.myrmec.engine.team;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.team.dto.AssignTeamMemberRequest;
import ai.myrmec.engine.team.dto.TeamMemberCandidate;
import ai.myrmec.engine.team.dto.TeamMemberListResponse;
import ai.myrmec.engine.team.dto.TeamMemberResponse;
import ai.myrmec.engine.team.dto.SystemWideUserSummary;
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
 * Manages Team-scoped user memberships backed by {@code user_roles} rows
 * where {@code team_id} is set.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TeamMemberService {

    private final TeamRepository teamRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;

    @Transactional(readOnly = true)
    public TeamMemberListResponse list(UUID teamId) {
        ensureTeamExists(teamId);

        List<UserRole> teamRoles = userRoleRepository.findByTeamId(teamId);
        // "Global users" panel: any user with a system-wide role who is NOT
        // yet a member of this Team. Only data-access roles (EDITOR/VIEWER)
        // and platform/org admins are surfaced; functional roles like
        // BUDGET_OWNER/APPROVER/AUDITOR are not part of the Team member UX.
        Set<UUID> teamMemberUserIds = teamRoles.stream()
                .map(UserRole::getUserId)
                .collect(Collectors.toSet());
        List<UserRole> systemRoles = userRoleRepository.findByTeamIdIsNull().stream()
                .filter(r -> !teamMemberUserIds.contains(r.getUserId()))
                .toList();

        Set<UUID> referencedUserIds = new HashSet<>();
        teamRoles.forEach(r -> referencedUserIds.add(r.getUserId()));
        systemRoles.forEach(r -> referencedUserIds.add(r.getUserId()));
        teamRoles.forEach(r -> {
            if (r.getGrantedByUserId() != null) {
                referencedUserIds.add(r.getGrantedByUserId());
            }
        });

        Map<UUID, User> usersById = userRepository.findAllById(referencedUserIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        List<TeamMemberResponse> members = teamRoles.stream()
                .map(r -> toMemberResponse(r, usersById))
                .filter(m -> m != null)
                .sorted(Comparator.comparing(TeamMemberResponse::getEmail,
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

        return TeamMemberListResponse.builder()
                .teamMembers(members)
                .systemWideUsers(systemWide)
                .build();
    }

    /**
     * Active users not yet assigned an explicit Team role on this Team,
     * shown in the "add member" picker.
     */
    @Transactional(readOnly = true)
    public List<TeamMemberCandidate> listCandidates(UUID teamId) {
        ensureTeamExists(teamId);

        Set<UUID> alreadyMembers = userRoleRepository.findByTeamId(teamId).stream()
                .map(UserRole::getUserId)
                .collect(Collectors.toSet());

        // PLATFORM_ADMIN / ORG_ADMIN have implicit access to every Team — hide them.
        // Global EDITOR/VIEWER users remain eligible for Team-scoped assignment.
        Set<UUID> systemAdminUserIds = new HashSet<>();
        userRoleRepository.findByRoleAndTeamIdIsNull(UserRole.Role.PLATFORM_ADMIN)
                .forEach(r -> systemAdminUserIds.add(r.getUserId()));
        userRoleRepository.findByRoleAndTeamIdIsNull(UserRole.Role.ORG_ADMIN)
                .forEach(r -> systemAdminUserIds.add(r.getUserId()));

        return userRepository.findByIsActiveTrueOrderByEmailAsc().stream()
                .filter(u -> !alreadyMembers.contains(u.getId()))
                .filter(u -> !systemAdminUserIds.contains(u.getId()))
                .map(u -> TeamMemberCandidate.builder()
                        .userId(u.getId())
                        .email(u.getEmail())
                        .name(u.getName())
                        .build())
                .toList();
    }

    /**
     * Grant an additional Team-scoped role to a user. A user may hold multiple
     * roles on the same Team (e.g. EDITOR + APPROVER); each role is a separate
     * {@code user_roles} row. Granting a role the user already holds on this Team
     * is a no-op and returns the existing row.
     */
    @Transactional
    public TeamMemberResponse assign(UUID teamId, AssignTeamMemberRequest request, UUID grantedByUserId) {
        ensureTeamExists(teamId);

        if (request.getRole() == UserRole.Role.PLATFORM_ADMIN
                || request.getRole() == UserRole.Role.ORG_ADMIN) {
            throw new BadRequestException(request.getRole() + " is system-wide and cannot be assigned at the Team scope");
        }

        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> ResourceNotFoundException.of("User", request.getUserId()));
        if (Boolean.FALSE.equals(user.getIsActive())) {
            throw new BadRequestException("User is inactive and cannot be assigned to a Team");
        }

        Map<UUID, User> ctx = Map.of(user.getId(), user);
        User grantedByEarly = grantedByUserId != null ? userRepository.findById(grantedByUserId).orElse(null) : null;

        Optional<UserRole> duplicate = userRoleRepository.findByUserIdAndTeamId(user.getId(), teamId).stream()
                .filter(r -> r.getRole() == request.getRole())
                .findFirst();
        if (duplicate.isPresent()) {
            return toMemberResponse(duplicate.get(), ctx, grantedByEarly);
        }

        UserRole row = new UserRole();
        row.setUserId(user.getId());
        row.setRole(request.getRole());
        row.setScopeType(UserRole.ScopeType.TEAM);
        row.setTeamId(teamId);
        row.setGrantedByUserId(grantedByUserId);
        row = userRoleRepository.save(row);

        log.info("Assigned user {} as {} on Team {} (granted by {})",
                user.getId(), request.getRole(), teamId, grantedByUserId);

        return toMemberResponse(row, ctx, grantedByEarly);
    }

    @Transactional
    public void remove(UUID teamId, UUID userId, UUID roleId) {
        ensureTeamExists(teamId);

        UserRole row = userRoleRepository.findById(roleId)
                .orElseThrow(() -> ResourceNotFoundException.of("TeamMemberRole", roleId));

        if (!teamId.equals(row.getTeamId()) || !userId.equals(row.getUserId())) {
            throw ResourceNotFoundException.of("TeamMemberRole", roleId);
        }
        if (row.getTeamId() == null) {
            // Defensive: this endpoint only manages Team-scoped rows.
            throw new BadRequestException("Cannot remove a system-wide role from a Team page");
        }

        userRoleRepository.delete(row);
        log.info("Removed user {} from Team {} (role row {})", userId, teamId, roleId);
    }

    private void ensureTeamExists(UUID teamId) {
        if (!teamRepository.existsById(teamId)) {
            throw ResourceNotFoundException.of("team", teamId);
        }
    }

    private TeamMemberResponse toMemberResponse(UserRole row, Map<UUID, User> usersById) {
        return toMemberResponse(row, usersById, Optional.ofNullable(row.getGrantedByUserId())
                .map(usersById::get).orElse(null));
    }

    private TeamMemberResponse toMemberResponse(UserRole row, Map<UUID, User> usersById, User grantedBy) {
        User user = usersById.get(row.getUserId());
        if (user == null) {
            // Orphaned role row (user deleted but FK missing) — skip in the listing.
            return null;
        }
        return TeamMemberResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .isActive(user.getIsActive())
                .role(row.getRole())
                .teamRoleId(row.getId())
                .grantedByUserId(row.getGrantedByUserId())
                .grantedByEmail(grantedBy != null ? grantedBy.getEmail() : null)
                .createdAt(row.getCreatedAt())
                .build();
    }
}
