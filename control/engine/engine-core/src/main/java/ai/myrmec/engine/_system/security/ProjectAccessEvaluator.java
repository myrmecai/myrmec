package ai.myrmec.engine._system.security;

import ai.myrmec.engine.group.GroupRepository;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectRepository;
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
 * SpEL access evaluator for project-scoped endpoints.
 *
 * <p>Wired via {@code @PreAuthorize("@projectAccess.canEdit(#projectId, authentication)")}.
 * Resolution order:
 * <ol>
 *   <li>Project-scoped role grant on this projectId.</li>
 *   <li>Group-scoped role grant on the project's group or any ancestor.</li>
 *   <li>System-wide role grant.</li>
 * </ol>
 *
 * <p>Each step uses {@link UserRole.Role#impliedRoles()} for implicit-role
 * expansion (e.g., {@code PROJECT_OWNER} satisfies an {@code EDITOR} check).
 *
 * <p>{@code canOwn} requires {@code PROJECT_OWNER} which is only meaningful at
 * project scope; {@code ORG_ADMIN} can additionally own (governance override).
 */
@Component("projectAccess")
@RequiredArgsConstructor
public class ProjectAccessEvaluator {

    private final ProjectRepository projectRepository;
    private final GroupRepository groupRepository;

    public boolean canView(UUID projectId, Authentication authentication) {
        return hasAccess(projectId, authentication, UserRole.Role.VIEWER);
    }

    public boolean canEdit(UUID projectId, Authentication authentication) {
        return hasAccess(projectId, authentication, UserRole.Role.EDITOR);
    }

    /**
     * Owner-level access: project delete, transfer-to-group, member management.
     * Granted to project-scoped {@code PROJECT_OWNER} or system-wide {@code ORG_ADMIN}.
     */
    public boolean canOwn(UUID projectId, Authentication authentication) {
        UserPrincipal user = principalOf(authentication);
        if (user == null || projectId == null) return false;

        // Project-scoped owner.
        if (user.hasProjectRoleDirect(projectId, UserRole.Role.PROJECT_OWNER)) {
            return true;
        }
        // Governance bypass: ORG_ADMIN at system can manage any project.
        return user.isOrgAdmin();
    }

    /**
     * Service-type gate (#77): does this project allow hosting services of the
     * given type? Wired via
     * {@code @PreAuthorize("@projectAccess.allowsServiceType(#projectId, 'WORKFLOW')")}
     * on every create of a service of type T. Unlike the role checks this is a
     * pure project-configuration gate — it does not consult the authenticated
     * principal. A missing project or an unknown type is denied (fail closed).
     */
    public boolean allowsServiceType(UUID projectId, String serviceType) {
        if (projectId == null || serviceType == null) return false;
        return projectRepository.findById(projectId)
                .map(Project::getAllowedServiceTypes)
                .map(types -> types.stream()
                        .anyMatch(t -> t != null && t.equalsIgnoreCase(serviceType.trim())))
                .orElse(false);
    }

    private boolean hasAccess(UUID projectId, Authentication authentication, UserRole.Role minimum) {
        UserPrincipal user = principalOf(authentication);
        if (user == null || projectId == null) return false;

        // 1. Project-scoped grant.
        if (user.hasProjectRoleDirect(projectId, minimum)) {
            return true;
        }

        // 2. Group-scoped grant on project's group + ancestors.
        Optional<Project> project = projectRepository.findById(projectId);
        if (project.isPresent() && project.get().getGroupId() != null) {
            UUID groupId = project.get().getGroupId();
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
