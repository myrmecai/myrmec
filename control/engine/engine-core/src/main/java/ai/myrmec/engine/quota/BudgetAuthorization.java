// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.quota;

import ai.myrmec.engine._system.security.GroupAccessEvaluator;
import ai.myrmec.engine._system.security.TeamAccessEvaluator;
import ai.myrmec.engine.assistant.Assistant;
import ai.myrmec.engine.assistant.AssistantRepository;
import ai.myrmec.engine.group.GroupRepository;
import ai.myrmec.engine.team.Team;
import ai.myrmec.engine.team.TeamRepository;
import ai.myrmec.engine.quota.dto.BudgetPermissions;
import ai.myrmec.engine.user.UserPrincipal;
import ai.myrmec.engine.user.UserRole;
import ai.myrmec.engine.workflow.Workflow;
import ai.myrmec.engine.workflow.WorkflowRepository;
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
 *   <li>Team-scoped {@code BUDGET_OWNER} can manage budgets for that
 *       Team and its services.</li>
 *   <li>{@code TEAM_OWNER} / {@code EDITOR} can carve service budgets
 *       (pause / resume / delete them) but cannot set Team/group/org ceilings.</li>
 *   <li>{@code ORG_ADMIN} and data-access roles can view budgets in their
 *       scope; they cannot create or mutate budgets.</li>
 * </ul>
 *
 * <p>The helper intentionally does <b>not</b> walk the Team/group ancestry
 * itself; it reuses the existing {@link TeamAccessEvaluator} and
 * {@link GroupAccessEvaluator} for data-access view checks, and adds the
 * budget-specific role grants on top.</p>
 */
@Component
@RequiredArgsConstructor
public class BudgetAuthorization {

    private final TeamAccessEvaluator teamAccess;
    private final GroupAccessEvaluator groupAccess;
    private final TeamRepository teamRepository;
    private final GroupRepository groupRepository;
    private final WorkflowRepository workflowRepository;
    private final AssistantRepository assistantRepository;

    /**
     * Resolve the Team ID from a SERVICE-scope scopeId (workflow / assistant
     * instance UUID).  Falls back to treating scopeId as a Team ID for
     * backward compatibility with legacy data.
     */
    private UUID resolveTeamIdForService(UUID scopeId) {
        if (scopeId == null) {
            return null;
        }
        Optional<Workflow> wf = workflowRepository.findById(scopeId);
        if (wf.isPresent()) {
            return wf.get().getTeam().getId();
        }
        Optional<Assistant> asst = assistantRepository.findById(scopeId);
        if (asst.isPresent()) {
            return asst.get().getTeamId();
        }
        // Legacy: scopeId might be the Team ID itself.
        if (teamRepository.existsById(scopeId)) {
            return scopeId;
        }
        return null;
    }

    /**
     * Permission snapshot for the given scope.
     *
     * @param scopeType quota scope tier; {@code ORG} ignores {@code scopeId}
     * @param scopeId   the group, Team, or service id; null for {@code ORG}
     */
    public BudgetPermissions permissionsFor(Quota.Scope scopeType, UUID scopeId, Authentication authentication) {
        UserPrincipal user = principalOf(authentication);
        if (user == null) {
            return BudgetPermissions.none();
        }

        // For SERVICE scope, resolve the Team ID from the instance.
        UUID effectiveteamId = scopeType == Quota.Scope.SERVICE
                ? resolveTeamIdForService(scopeId) : scopeId;

        boolean platformAdmin = user.hasSystemRole(UserRole.Role.PLATFORM_ADMIN);
        boolean systemBudgetOwner = user.hasSystemRole(UserRole.Role.BUDGET_OWNER);
        boolean orgAdmin = user.isOrgAdmin();
        boolean systemCanManage = platformAdmin || systemBudgetOwner;

        boolean canView = canView(scopeType, effectiveteamId, user, authentication, platformAdmin, orgAdmin, systemBudgetOwner);
        boolean canDefineCeiling = canDefineCeiling(scopeType, effectiveteamId, user, systemCanManage);
        boolean canDefineReservation = canDefineCeiling && scopeType != Quota.Scope.ORG;
        boolean canDefineService = canDefineServiceBudget(scopeType, effectiveteamId, user, systemCanManage);
        boolean canMutateServiceBudget = scopeType == Quota.Scope.SERVICE
                && (canDefineService || hasProjectOwnerOrEditor(user, effectiveteamId));

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
            case TEAM -> user.hasTeamRoleDirect(scopeId, UserRole.Role.BUDGET_OWNER)
                    || hasProjectOwnerOrEditor(user, scopeId)
                    || user.hasTeamRoleDirect(scopeId, UserRole.Role.VIEWER)
                    || hasGroupBudgetOwnerOnTeam(user, scopeId)
                    || teamAccess.canView(scopeId, authentication);
            case SERVICE -> canView(Quota.Scope.TEAM, scopeId, user, authentication,
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
        // Ceilings and reservations only exist at ORG / GROUP / Team scope.
        if (scopeType == Quota.Scope.SERVICE) {
            return false;
        }
        if (systemCanManage) {
            return true;
        }

        return switch (scopeType) {
            case ORG -> false;
            case GROUP -> user.hasGroupRoleDirect(scopeId, UserRole.Role.BUDGET_OWNER);
            case TEAM -> user.hasTeamRoleDirect(scopeId, UserRole.Role.BUDGET_OWNER)
                    || hasGroupBudgetOwnerOnTeam(user, scopeId);
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
        return user.hasTeamRoleDirect(scopeId, UserRole.Role.BUDGET_OWNER)
                || hasGroupBudgetOwnerOnTeam(user, scopeId)
                || hasProjectOwnerOrEditor(user, scopeId);
    }

    private boolean hasProjectOwnerOrEditor(UserPrincipal user, UUID teamId) {
        return user.hasTeamRoleDirect(teamId, UserRole.Role.TEAM_OWNER)
                || user.hasTeamRoleDirect(teamId, UserRole.Role.EDITOR);
    }

    private boolean hasGroupBudgetOwnerOnTeam(UserPrincipal user, UUID teamId) {
        if (teamId == null) {
            return false;
        }
        Optional<Team> team = teamRepository.findById(teamId);
        if (team.isEmpty() || team.get().getGroupId() == null) {
            return false;
        }
        UUID groupId = team.get().getGroupId();
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
