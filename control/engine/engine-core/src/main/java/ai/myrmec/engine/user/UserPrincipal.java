package ai.myrmec.engine.user;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.security.Principal;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Principal for an authenticated user.
 *
 * <p>JWT role claims use scope-prefixed strings:
 * <ul>
 *   <li>{@code sys:ROLE} — system-wide</li>
 *   <li>{@code grp:<groupId>:ROLE} — group-scoped</li>
 *   <li>{@code proj:<teamId>:ROLE} — Team-scoped</li>
 * </ul>
 *
 * <p>The principal exposes per-scope role checks with implicit-role expansion
 * (see {@link UserRole.Role#impliedRoles()}). It does <b>not</b> walk group
 * ancestry — that requires DB access and is performed by the access evaluator
 * beans (see {@code TeamAccessEvaluator}, {@code GroupAccessEvaluator}).
 */
@Getter
public class UserPrincipal implements Principal {

    private static final String SYS_PREFIX = "sys:";
    private static final String GRP_PREFIX = "grp:";
    private static final String PROJ_PREFIX = "proj:";

    private final UUID userId;
    private final String name;
    private final String email;
    private final List<String> roles;

    /** Effective system-wide roles (with implicit expansion). */
    private final Set<UserRole.Role> effectiveSystemRoles;
    /** Effective group-scoped roles per group id (with implicit expansion). */
    private final Map<UUID, Set<UserRole.Role>> effectiveGroupRoles;
    /** Effective Team-scoped roles per Team id (with implicit expansion). */
    private final Map<UUID, Set<UserRole.Role>> effectiveTeamRoles;

    public UserPrincipal(UUID userId, String name, String email, List<String> roles) {
        this.userId = userId;
        this.name = name;
        this.email = email;
        this.roles = roles;

        Set<UserRole.Role> sys = EnumSet.noneOf(UserRole.Role.class);
        Map<UUID, Set<UserRole.Role>> grp = new HashMap<>();
        Map<UUID, Set<UserRole.Role>> proj = new HashMap<>();

        if (roles != null) {
            for (String claim : roles) {
                if (claim == null) continue;
                parseAndAdd(claim, sys, grp, proj);
            }
        }

        this.effectiveSystemRoles = sys;
        this.effectiveGroupRoles = grp;
        this.effectiveTeamRoles = proj;
    }

    private static void parseAndAdd(String claim,
                                    Set<UserRole.Role> sys,
                                    Map<UUID, Set<UserRole.Role>> grp,
                                    Map<UUID, Set<UserRole.Role>> proj) {
        if (claim.startsWith(SYS_PREFIX)) {
            UserRole.Role role = parseRole(claim.substring(SYS_PREFIX.length()));
            if (role != null) sys.addAll(role.impliedRoles());
        } else if (claim.startsWith(GRP_PREFIX)) {
            String rest = claim.substring(GRP_PREFIX.length());
            int sep = rest.lastIndexOf(':');
            if (sep <= 0) return;
            UUID gid = parseUuid(rest.substring(0, sep));
            UserRole.Role role = parseRole(rest.substring(sep + 1));
            if (gid != null && role != null) {
                grp.computeIfAbsent(gid, k -> EnumSet.noneOf(UserRole.Role.class))
                        .addAll(role.impliedRoles());
            }
        } else if (claim.startsWith(PROJ_PREFIX)) {
            String rest = claim.substring(PROJ_PREFIX.length());
            int sep = rest.lastIndexOf(':');
            if (sep <= 0) return;
            UUID pid = parseUuid(rest.substring(0, sep));
            UserRole.Role role = parseRole(rest.substring(sep + 1));
            if (pid != null && role != null) {
                proj.computeIfAbsent(pid, k -> EnumSet.noneOf(UserRole.Role.class))
                        .addAll(role.impliedRoles());
            }
        }
    }

    private static UserRole.Role parseRole(String s) {
        try { return UserRole.Role.valueOf(s); } catch (IllegalArgumentException e) { return null; }
    }

    private static UUID parseUuid(String s) {
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    @Override
    public String getName() {
        return name;
    }

    /** True if user holds the system-wide PLATFORM_ADMIN role. */
    public boolean isPlatformAdmin() {
        return hasSystemRole(UserRole.Role.PLATFORM_ADMIN);
    }

    /** True if user holds the system-wide ORG_ADMIN role. */
    public boolean isOrgAdmin() {
        return hasSystemRole(UserRole.Role.ORG_ADMIN);
    }

    /** Check a system-wide role (with implicit expansion). */
    public boolean hasSystemRole(UserRole.Role role) {
        return effectiveSystemRoles.contains(role);
    }

    /** Check a role granted directly at this group (no ancestor walk). */
    public boolean hasGroupRoleDirect(UUID groupId, UserRole.Role role) {
        if (groupId == null) return false;
        Set<UserRole.Role> roles = effectiveGroupRoles.get(groupId);
        return roles != null && roles.contains(role);
    }

    /** Check a Team-scoped role. */
    public boolean hasTeamRoleDirect(UUID teamId, UserRole.Role role) {
        if (teamId == null) return false;
        Set<UserRole.Role> roles = effectiveTeamRoles.get(teamId);
        return roles != null && roles.contains(role);
    }

    /**
     * Check a group role considering a precomputed ancestor chain.
     * Caller (the access evaluator) supplies the group plus all ancestor IDs.
     */
    public boolean hasGroupRoleInChain(Set<UUID> groupAndAncestors, UserRole.Role role) {
        if (groupAndAncestors == null) return false;
        for (UUID gid : groupAndAncestors) {
            if (hasGroupRoleDirect(gid, role)) return true;
        }
        return false;
    }

    /** All group ids for which the user has any direct role grant. */
    public Set<UUID> groupIdsWithAnyRole() {
        return new HashSet<>(effectiveGroupRoles.keySet());
    }

    /** All Team ids for which the user has any direct role grant. */
    public Set<UUID> teamIdsWithAnyRole() {
        return new HashSet<>(effectiveTeamRoles.keySet());
    }
}
