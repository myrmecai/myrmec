package ai.myrmec.engine._system.security;

import ai.myrmec.engine.group.GroupRepository;
import ai.myrmec.engine.team.Team;
import ai.myrmec.engine.team.TeamRepository;
import ai.myrmec.engine.user.UserPrincipal;
import ai.myrmec.engine.user.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * SpEL access evaluator for Team-scoped endpoints.
 *
 * <p>Wired via {@code @PreAuthorize("@teamAccess.canEdit(#teamId, authentication)")}.
 * Resolution order:
 * <ol>
 *   <li>Team-scoped role grant on this teamId.</li>
 *   <li>Group-scoped role grant on the Team's group or any ancestor.</li>
 *   <li>System-wide role grant.</li>
 * </ol>
 *
 * <p>Each step uses {@link UserRole.Role#impliedRoles()} for implicit-role
 * expansion (e.g., {@code TEAM_OWNER} satisfies an {@code EDITOR} check).
 *
 * <p>{@code canOwn} requires {@code TEAM_OWNER} which is only meaningful at
 * Team scope; {@code ORG_ADMIN} can additionally own (governance override).
 */
@Component("teamAccess")
@RequiredArgsConstructor
public class TeamAccessEvaluator {

    private final TeamRepository teamRepository;
    private final GroupRepository groupRepository;

    public boolean canView(UUID teamId, Authentication authentication) {
        return hasAccess(teamId, authentication, UserRole.Role.VIEWER);
    }

    public boolean canEdit(UUID teamId, Authentication authentication) {
        return hasAccess(teamId, authentication, UserRole.Role.EDITOR);
    }

    /**
     * Owner-level access: Team delete, transfer-to-group, member management.
     * Granted to Team-scoped {@code TEAM_OWNER} or system-wide {@code ORG_ADMIN}.
     */
    public boolean canOwn(UUID teamId, Authentication authentication) {
        UserPrincipal user = principalOf(authentication);
        if (user == null || teamId == null) return false;

        // Team-scoped owner.
        if (user.hasTeamRoleDirect(teamId, UserRole.Role.TEAM_OWNER)) {
            return true;
        }
        // Governance bypass: ORG_ADMIN at system can manage any Team.
        return user.isOrgAdmin();
    }

    /**
     * Service-type gate (#77): does this Team allow hosting services of the
     * given type? Wired via
     * {@code @PreAuthorize("@teamAccess.allowsServiceType(#teamId, 'WORKFLOW')")}
     * on every create of a service of type T. Unlike the role checks this is a
     * pure Team-configuration gate — it does not consult the authenticated
     * principal. A missing Team or an unknown type is denied (fail closed).
     */
    public boolean allowsServiceType(UUID teamId, String serviceType) {
        if (teamId == null || serviceType == null) return false;
        return teamRepository.findById(teamId)
                .map(Team::getAllowedServiceTypes)
                .map(types -> types.stream()
                        .anyMatch(t -> t != null && t.equalsIgnoreCase(serviceType.trim())))
                .orElse(false);
    }

    private boolean hasAccess(UUID teamId, Authentication authentication, UserRole.Role minimum) {
        UserPrincipal user = principalOf(authentication);
        if (user == null || teamId == null) return false;

        // 1. Team-scoped grant.
        if (user.hasTeamRoleDirect(teamId, minimum)) {
            return true;
        }

        // 2. Group-scoped grant on Team's group + ancestors.
        Optional<Team> team = teamRepository.findById(teamId);
        if (team.isPresent() && team.get().getGroupId() != null) {
            UUID groupId = team.get().getGroupId();
            List<UUID> ancestorList = groupRepository.findAncestorIds(groupId);
            Set<UUID> ancestors = new HashSet<>(ancestorList);
            ancestors.add(groupId); // defensive — query already includes self
            if (user.hasGroupRoleInChain(ancestors, minimum)) {
                return true;
            }
        }

        // 3. System-wide grant.
        return user.hasSystemRole(minimum);
    }

    private static UserPrincipal principalOf(Authentication authentication) {
        if (authentication == null) return null;
        Object p = authentication.getPrincipal();
        return p instanceof UserPrincipal up ? up : null;
    }
}
