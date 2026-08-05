// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.quota;

import ai.myrmec.engine._system.security.GroupAccessEvaluator;
import ai.myrmec.engine._system.security.ProjectAccessEvaluator;
import ai.myrmec.engine.group.GroupRepository;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectRepository;
import ai.myrmec.engine.quota.dto.BudgetPermissions;
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
 * Scope-aware authorization helper for budget/quota management.
 *
 * <p>Implements the role model from the quota UI design:
 * <ul>
 *   <li>{@code PLATFORM_ADMIN} and {@code BUDGET_OWNER} (system) can define
 *       ceilings at any scope and view every budget.</li>
 *   <li>Group-scoped {@code BUDGET_OWNER} can manage budgets for that group
 *       and its descendant projects / services.</li>
 *   <li>Project-scoped {@code BUDGET_OWNER} can manage budgets for that
 *       project and its services.</li>
 *   <li>{@code PROJECT_OWNER} / {@code EDITOR} can carve service budgets
 *       (pause / resume / delete them) but cannot set project/group/org ceilings.</li>
 *   <li>{@code ORG_ADMIN} and data-access roles can view budgets in their
 *       scope; they cannot create or mutate budgets.</li>
 * </ul>
 *
 * <p>The helper intentionally does <b>not</b> walk the project/group ancestry
 * itself; it reuses the existing {@link ProjectAccessEvaluator} and
 * {@link GroupAccessEvaluator} for data-access view checks, and adds the
 * budget-specific role grants on top.</p>
 */
@Component
@RequiredArgsConstructor
public class BudgetAuthorization {

    private final ProjectAccessEvaluator projectAccess;
    private final GroupAccessEvaluator groupAccess;
    private final ProjectRepository projectRepository;
    private final GroupRepository groupRepository;

    /**
     * Permission snapshot for the given scope.
     *
     * @param scopeType quota scope tier; {@code ORG} ignores {@code scopeId}
     * @param scopeId   the group, project, or service id; null for {@code ORG}
     */
    public BudgetPermissions permissionsFor(Quota.Scope scopeType, UUID scopeId, Authentication authentication) {
        UserPrincipal user = principalOf(authentication);
        if (user == null) {
            return BudgetPermissions.none();
        }

        boolean platformAdmin = user.hasSystemRole(UserRole.Role.PLATFORM_ADMIN);
        boolean systemBudgetOwner = user.hasSystemRole(UserRole.Role.BUDGET_OWNER);
        boolean orgAdmin = user.isOrgAdmin();
        boolean systemCanManage = platformAdmin || systemBudgetOwner;

        boolean canView = canView(scopeType, scopeId, user, authentication, platformAdmin, orgAdmin, systemBudgetOwner);
        boolean canDefineCeiling = canDefineCeiling(scopeType, scopeId, user, systemCanManage);
        boolean canDefineReservation = canDefineCeiling && scopeType != Quota.Scope.ORG;
        boolean canDefineService = canDefineServiceBudget(scopeType, scopeId, user, systemCanManage);
        boolean canMutateServiceBudget = scopeType == Quota.Scope.SERVICE
                && (canDefineService || hasProjectOwnerOrEditor(user, scopeId));

        return new BudgetPermissions(
                canView,
                canDefineCeiling,
                canDefineReservation,
                canDefineService,
                canDefineCeiling || canMutateServiceBudget,
                canDefineCeiling || canMutateServiceBudget);
    }

    private boolean canView(Quota.Scope scopeType,
                            UUID scopeId,
                            UserPrincipal user,
                            Authentication authentication,
                            boolean platformAdmin,
                            boolean orgAdmin,
                            boolean systemBudgetOwner) {
        if (platformAdmin || orgAdmin || systemBudgetOwner) {
            return true;
        }

        return switch (scopeType) {
            case ORG -> false;
            case GROUP -> hasGroupBudgetOwnerOnGroup(user, scopeId)
                    || groupAccess.canView(scopeId, authentication);
            case PROJECT -> user.hasProjectRoleDirect(scopeId, UserRole.Role.BUDGET_OWNER)
                    || hasProjectOwnerOrEditor(user, scopeId)
                    || user.hasProjectRoleDirect(scopeId, UserRole.Role.VIEWER)
                    || hasGroupBudgetOwnerOnProject(user, scopeId)
                    || projectAccess.canView(scopeId, authentication);
            case SERVICE -> canView(Quota.Scope.PROJECT, scopeId, user, authentication,
                    platformAdmin, orgAdmin, systemBudgetOwner);
        };
    }

    private boolean hasGroupBudgetOwnerOnGroup(UserPrincipal user, UUID groupId) {
        if (groupId == null) {
            return false;
        }
        List<UUID> ancestorIds = groupRepository.findAncestorIds(groupId);
        Set<UUID> chain = new HashSet<>(ancestorIds);
        chain.add(groupId);
        for (UUID id : chain) {
            if (user.hasGroupRoleDirect(id, UserRole.Role.BUDGET_OWNER)) {
                return true;
            }
        }
        return false;
    }

    private boolean canDefineCeiling(Quota.Scope scopeType,
                                     UUID scopeId,
                                     UserPrincipal user,
                                     boolean systemCanManage) {
        // Ceilings and reservations only exist at ORG / GROUP / PROJECT scope.
        if (scopeType == Quota.Scope.SERVICE) {
            return false;
        }
        if (systemCanManage) {
            return true;
        }

        return switch (scopeType) {
            case ORG -> false;
            case GROUP -> user.hasGroupRoleDirect(scopeId, UserRole.Role.BUDGET_OWNER);
            case PROJECT -> user.hasProjectRoleDirect(scopeId, UserRole.Role.BUDGET_OWNER)
                    || hasGroupBudgetOwnerOnProject(user, scopeId);
            case SERVICE -> false;
        };
    }

    private boolean canDefineServiceBudget(Quota.Scope scopeType,
                                           UUID scopeId,
                                           UserPrincipal user,
                                           boolean systemCanManage) {
        if (scopeType != Quota.Scope.SERVICE) {
            return false;
        }
        if (systemCanManage) {
            return true;
        }
        return user.hasProjectRoleDirect(scopeId, UserRole.Role.BUDGET_OWNER)
                || hasGroupBudgetOwnerOnProject(user, scopeId)
                || hasProjectOwnerOrEditor(user, scopeId);
    }

    private boolean hasProjectOwnerOrEditor(UserPrincipal user, UUID projectId) {
        return user.hasProjectRoleDirect(projectId, UserRole.Role.PROJECT_OWNER)
                || user.hasProjectRoleDirect(projectId, UserRole.Role.EDITOR);
    }

    private boolean hasGroupBudgetOwnerOnProject(UserPrincipal user, UUID projectId) {
        if (projectId == null) {
            return false;
        }
        Optional<Project> project = projectRepository.findById(projectId);
        if (project.isEmpty() || project.get().getGroupId() == null) {
            return false;
        }
        UUID groupId = project.get().getGroupId();
        List<UUID> ancestorIds = groupRepository.findAncestorIds(groupId);
        Set<UUID> chain = new HashSet<>(ancestorIds);
        chain.add(groupId);

        for (UUID id : chain) {
            if (user.hasGroupRoleDirect(id, UserRole.Role.BUDGET_OWNER)) {
                return true;
            }
        }
        return false;
    }

    private static UserPrincipal principalOf(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        Object p = authentication.getPrincipal();
        return p instanceof UserPrincipal up ? up : null;
    }
}
