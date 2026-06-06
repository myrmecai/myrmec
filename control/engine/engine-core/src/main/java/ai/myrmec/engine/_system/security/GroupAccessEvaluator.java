package ai.myrmec.engine._system.security;

import ai.myrmec.engine.group.GroupRepository;
import ai.myrmec.engine.user.UserPrincipal;
import ai.myrmec.engine.user.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * SpEL access evaluator for group-scoped endpoints.
 *
 * <p>Wired via {@code @PreAuthorize("@groupAccess.canEdit(#groupId, authentication)")}.
 * Walks the group's ancestor chain (recursive CTE) and checks for direct or
 * implied role grants at any level. Falls back to system-wide role.
 *
 * <p>{@code canManage} corresponds to administrative changes (rename, delete,
 * member assignment) and requires system-wide {@code ORG_ADMIN}: groups are a
 * governance concept and management is intentionally not delegable through the
 * group hierarchy itself.
 */
@Component("groupAccess")
@RequiredArgsConstructor
public class GroupAccessEvaluator {

    private final GroupRepository groupRepository;

    public boolean canView(UUID groupId, Authentication authentication) {
        return hasAccess(groupId, authentication, UserRole.Role.VIEWER);
    }

    public boolean canEdit(UUID groupId, Authentication authentication) {
        return hasAccess(groupId, authentication, UserRole.Role.EDITOR);
    }

    /**
     * True if the user can manage group metadata (rename / delete / membership).
     * System-wide {@code ORG_ADMIN} only.
     */
    public boolean canManage(UUID groupId, Authentication authentication) {
        UserPrincipal user = principalOf(authentication);
        return user != null && user.isOrgAdmin();
    }

    private boolean hasAccess(UUID groupId, Authentication authentication, UserRole.Role minimum) {
        UserPrincipal user = principalOf(authentication);
        if (user == null || groupId == null) return false;

        List<UUID> ancestorList = groupRepository.findAncestorIds(groupId);
        Set<UUID> ancestors = new HashSet<>(ancestorList);
        ancestors.add(groupId);
        if (user.hasGroupRoleInChain(ancestors, minimum)) {
            return true;
        }

        return user.hasSystemRole(minimum);
    }

    private static UserPrincipal principalOf(Authentication authentication) {
        if (authentication == null) return null;
        Object p = authentication.getPrincipal();
        return p instanceof UserPrincipal up ? up : null;
    }
}
