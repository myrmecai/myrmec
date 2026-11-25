// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.quota;

import ai.myrmec.engine._system.security.GroupAccessEvaluator;
import ai.myrmec.engine._system.security.TeamAccessEvaluator;
import ai.myrmec.engine.assistant.AssistantRepository;
import ai.myrmec.engine.group.GroupRepository;
import ai.myrmec.engine.team.Team;
import ai.myrmec.engine.team.TeamRepository;
import ai.myrmec.engine.quota.dto.BudgetPermissions;
import ai.myrmec.engine.user.UserPrincipal;
import ai.myrmec.engine.user.UserRole;
import ai.myrmec.engine.workflow.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BudgetAuthorization} role/scope decisions.
 *
 * <p>Security evaluators and repositories are mocked so the tests focus only
 * on the budget-specific permission matrix.
 */
@ExtendWith(MockitoExtension.class)
class BudgetAuthorizationTest {

    @Mock private TeamAccessEvaluator teamAccess;
    @Mock private GroupAccessEvaluator groupAccess;
    @Mock private TeamRepository TeamRepository;
    @Mock private GroupRepository groupRepository;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private AssistantRepository assistantRepository;

    private BudgetAuthorization authorization;

    @BeforeEach
    void setUp() {
        authorization = new BudgetAuthorization(teamAccess, groupAccess, TeamRepository,
                groupRepository, workflowRepository, assistantRepository);
        lenient().when(teamAccess.canView(any(), any())).thenReturn(false);
        lenient().when(groupAccess.canView(any(), any())).thenReturn(false);
        // For SERVICE-scope permission checks, the service instance is
        // resolved to a Team ID. In these unit tests the "service"
        // scopeId is a team UUID, so the team-exists fallback
        // must return true.
        lenient().when(TeamRepository.existsById(any(UUID.class))).thenReturn(true);
    }

    private static Authentication auth(String... claims) {
        UserPrincipal principal = new UserPrincipal(UUID.randomUUID(), "Test", "test@local", List.of(claims));
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);
        return authentication;
    }

    @Test
    void anonymousUserHasNoPermissions() {
        BudgetPermissions p = authorization.permissionsFor(Quota.Scope.ORG, null, null);
        assertThat(p).isEqualTo(BudgetPermissions.none());
    }

    @Test
    void platformAdminCanDefineCeilingsEverywhere() {
        Authentication auth = auth("sys:PLATFORM_ADMIN");

        BudgetPermissions org = authorization.permissionsFor(Quota.Scope.ORG, null, auth);
        assertThat(org.canView()).isTrue();
        assertThat(org.canCreateCeiling()).isTrue();
        assertThat(org.canCreateReservation()).isFalse();
        assertThat(org.canCreateServiceBudget()).isFalse();
        assertThat(org.canPauseResume()).isTrue();
        assertThat(org.canDelete()).isTrue();

        UUID groupId = UUID.randomUUID();
        BudgetPermissions group = authorization.permissionsFor(Quota.Scope.GROUP, groupId, auth);
        assertThat(group.canView()).isTrue();
        assertThat(group.canCreateCeiling()).isTrue();
        assertThat(group.canCreateReservation()).isTrue();
        assertThat(group.canCreateServiceBudget()).isFalse();
        assertThat(group.canPauseResume()).isTrue();

        UUID teamId = UUID.randomUUID();
        BudgetPermissions team = authorization.permissionsFor(Quota.Scope.TEAM, teamId, auth);
        assertThat(team.canCreateReservation()).isTrue();
        assertThat(team.canCreateServiceBudget()).isFalse();

        BudgetPermissions service = authorization.permissionsFor(Quota.Scope.SERVICE, teamId, auth);
        assertThat(service.canCreateCeiling()).isFalse();
        assertThat(service.canCreateReservation()).isFalse();
        assertThat(service.canCreateServiceBudget()).isTrue();
    }

    @Test
    void systemBudgetOwnerCanDefineAtAnyScope() {
        Authentication auth = auth("sys:BUDGET_OWNER");
        UUID groupId = UUID.randomUUID();
        BudgetPermissions group = authorization.permissionsFor(Quota.Scope.GROUP, groupId, auth);
        assertThat(group.canCreateCeiling()).isTrue();
        assertThat(group.canCreateReservation()).isTrue();

        UUID teamId = UUID.randomUUID();
        BudgetPermissions team = authorization.permissionsFor(Quota.Scope.TEAM, teamId, auth);
        assertThat(team.canCreateCeiling()).isTrue();
        assertThat(team.canCreateReservation()).isTrue();

        BudgetPermissions service = authorization.permissionsFor(Quota.Scope.SERVICE, teamId, auth);
        assertThat(service.canCreateServiceBudget()).isTrue();
    }

    @Test
    void orgAdminCanViewOnly() {
        Authentication auth = auth("sys:ORG_ADMIN");
        UUID teamId = UUID.randomUUID();
        BudgetPermissions team = authorization.permissionsFor(Quota.Scope.TEAM, teamId, auth);

        assertThat(team.canView()).isTrue();
        assertThat(team.canCreateCeiling()).isFalse();
        assertThat(team.canCreateReservation()).isFalse();
        assertThat(team.canCreateServiceBudget()).isFalse();
        assertThat(team.canPauseResume()).isFalse();
        assertThat(team.canDelete()).isFalse();
    }

    @Test
    void groupBudgetOwnerCanManageOwnGroupAndDescendantProjects() {
        UUID groupId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        when(TeamRepository.findById(teamId)).thenReturn(Optional.of(projectWithGroup(teamId, groupId)));
        when(groupRepository.findAncestorIds(groupId)).thenReturn(List.of());

        Authentication auth = auth("grp:" + groupId + ":BUDGET_OWNER");

        BudgetPermissions group = authorization.permissionsFor(Quota.Scope.GROUP, groupId, auth);
        assertThat(group.canView()).isTrue();
        assertThat(group.canCreateCeiling()).isTrue();
        assertThat(group.canCreateReservation()).isTrue();

        BudgetPermissions team = authorization.permissionsFor(Quota.Scope.TEAM, teamId, auth);
        assertThat(team.canView()).isTrue();
        assertThat(team.canCreateCeiling()).isTrue();
        assertThat(team.canCreateReservation()).isTrue();

        BudgetPermissions service = authorization.permissionsFor(Quota.Scope.SERVICE, teamId, auth);
        assertThat(service.canView()).isTrue();
        assertThat(service.canCreateServiceBudget()).isTrue();
    }

    @Test
    void projectBudgetOwnerCanManageProjectAndServices() {
        UUID teamId = UUID.randomUUID();

        Authentication auth = auth("proj:" + teamId + ":BUDGET_OWNER");

        BudgetPermissions team = authorization.permissionsFor(Quota.Scope.TEAM, teamId, auth);
        assertThat(team.canView()).isTrue();
        assertThat(team.canCreateCeiling()).isTrue();
        assertThat(team.canCreateReservation()).isTrue();
        assertThat(team.canCreateServiceBudget()).isFalse();
        assertThat(team.canPauseResume()).isTrue();
        assertThat(team.canDelete()).isTrue();

        BudgetPermissions service = authorization.permissionsFor(Quota.Scope.SERVICE, teamId, auth);
        assertThat(service.canCreateCeiling()).isFalse();
        assertThat(service.canCreateServiceBudget()).isTrue();
        assertThat(service.canPauseResume()).isTrue();
    }

    @Test
    void projectOwnerCanCarveServiceBudgetsButNotProjectCeilings() {
        UUID teamId = UUID.randomUUID();
        when(TeamRepository.findById(teamId)).thenReturn(Optional.of(projectWithGroup(teamId, null)));

        Authentication auth = auth("proj:" + teamId + ":TEAM_OWNER");

        BudgetPermissions team = authorization.permissionsFor(Quota.Scope.TEAM, teamId, auth);
        assertThat(team.canView()).isTrue();
        assertThat(team.canCreateCeiling()).isFalse();
        assertThat(team.canCreateReservation()).isFalse();
        assertThat(team.canPauseResume()).isFalse();
        assertThat(team.canDelete()).isFalse();

        BudgetPermissions service = authorization.permissionsFor(Quota.Scope.SERVICE, teamId, auth);
        assertThat(service.canCreateServiceBudget()).isTrue();
        assertThat(service.canCreateReservation()).isFalse();
        assertThat(service.canPauseResume()).isTrue();
        assertThat(service.canDelete()).isTrue();
    }

    @Test
    void editorCanCarveServiceBudgets() {
        UUID teamId = UUID.randomUUID();
        when(TeamRepository.findById(teamId)).thenReturn(Optional.of(projectWithGroup(teamId, null)));

        Authentication auth = auth("proj:" + teamId + ":EDITOR");

        BudgetPermissions service = authorization.permissionsFor(Quota.Scope.SERVICE, teamId, auth);
        assertThat(service.canCreateServiceBudget()).isTrue();
        assertThat(service.canPauseResume()).isTrue();
        assertThat(service.canDelete()).isTrue();

        BudgetPermissions team = authorization.permissionsFor(Quota.Scope.TEAM, teamId, auth);
        assertThat(team.canCreateCeiling()).isFalse();
        assertThat(team.canCreateServiceBudget()).isFalse();
    }

    @Test
    void viewerCannotMutateBudgets() {
        UUID teamId = UUID.randomUUID();
        when(TeamRepository.findById(teamId)).thenReturn(Optional.of(projectWithGroup(teamId, null)));

        Authentication auth = auth("proj:" + teamId + ":VIEWER");
        BudgetPermissions p = authorization.permissionsFor(Quota.Scope.TEAM, teamId, auth);

        assertThat(p.canView()).isTrue();
        assertThat(p.canCreateCeiling()).isFalse();
        assertThat(p.canCreateReservation()).isFalse();
        assertThat(p.canCreateServiceBudget()).isFalse();
        assertThat(p.canPauseResume()).isFalse();
        assertThat(p.canDelete()).isFalse();
    }

    @Test
    void groupBudgetOwnerCannotViewUnrelatedGroup() {
        UUID ownedGroup = UUID.randomUUID();
        UUID otherGroup = UUID.randomUUID();

        Authentication auth = auth("grp:" + ownedGroup + ":BUDGET_OWNER");
        BudgetPermissions p = authorization.permissionsFor(Quota.Scope.GROUP, otherGroup, auth);

        assertThat(p.canView()).isFalse();
        assertThat(p.canCreateCeiling()).isFalse();
    }

    private static team projectWithGroup(UUID teamId, UUID groupId) {
        team p = new team();
        p.setId(teamId);
        p.setGroupId(groupId);
        return p;
    }
}
