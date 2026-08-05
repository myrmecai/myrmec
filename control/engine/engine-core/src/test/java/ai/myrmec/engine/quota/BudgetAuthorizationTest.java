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

    @Mock private ProjectAccessEvaluator projectAccess;
    @Mock private GroupAccessEvaluator groupAccess;
    @Mock private ProjectRepository projectRepository;
    @Mock private GroupRepository groupRepository;

    private BudgetAuthorization authorization;

    @BeforeEach
    void setUp() {
        authorization = new BudgetAuthorization(projectAccess, groupAccess, projectRepository, groupRepository);
        lenient().when(projectAccess.canView(any(), any())).thenReturn(false);
        lenient().when(groupAccess.canView(any(), any())).thenReturn(false);
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

        UUID projectId = UUID.randomUUID();
        BudgetPermissions project = authorization.permissionsFor(Quota.Scope.PROJECT, projectId, auth);
        assertThat(project.canCreateReservation()).isTrue();
        assertThat(project.canCreateServiceBudget()).isFalse();

        BudgetPermissions service = authorization.permissionsFor(Quota.Scope.SERVICE, projectId, auth);
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

        UUID projectId = UUID.randomUUID();
        BudgetPermissions project = authorization.permissionsFor(Quota.Scope.PROJECT, projectId, auth);
        assertThat(project.canCreateCeiling()).isTrue();
        assertThat(project.canCreateReservation()).isTrue();

        BudgetPermissions service = authorization.permissionsFor(Quota.Scope.SERVICE, projectId, auth);
        assertThat(service.canCreateServiceBudget()).isTrue();
    }

    @Test
    void orgAdminCanViewOnly() {
        Authentication auth = auth("sys:ORG_ADMIN");
        UUID projectId = UUID.randomUUID();
        BudgetPermissions project = authorization.permissionsFor(Quota.Scope.PROJECT, projectId, auth);

        assertThat(project.canView()).isTrue();
        assertThat(project.canCreateCeiling()).isFalse();
        assertThat(project.canCreateReservation()).isFalse();
        assertThat(project.canCreateServiceBudget()).isFalse();
        assertThat(project.canPauseResume()).isFalse();
        assertThat(project.canDelete()).isFalse();
    }

    @Test
    void groupBudgetOwnerCanManageOwnGroupAndDescendantProjects() {
        UUID groupId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(projectWithGroup(projectId, groupId)));
        when(groupRepository.findAncestorIds(groupId)).thenReturn(List.of());

        Authentication auth = auth("grp:" + groupId + ":BUDGET_OWNER");

        BudgetPermissions group = authorization.permissionsFor(Quota.Scope.GROUP, groupId, auth);
        assertThat(group.canView()).isTrue();
        assertThat(group.canCreateCeiling()).isTrue();
        assertThat(group.canCreateReservation()).isTrue();

        BudgetPermissions project = authorization.permissionsFor(Quota.Scope.PROJECT, projectId, auth);
        assertThat(project.canView()).isTrue();
        assertThat(project.canCreateCeiling()).isTrue();
        assertThat(project.canCreateReservation()).isTrue();

        BudgetPermissions service = authorization.permissionsFor(Quota.Scope.SERVICE, projectId, auth);
        assertThat(service.canView()).isTrue();
        assertThat(service.canCreateServiceBudget()).isTrue();
    }

    @Test
    void projectBudgetOwnerCanManageProjectAndServices() {
        UUID projectId = UUID.randomUUID();

        Authentication auth = auth("proj:" + projectId + ":BUDGET_OWNER");

        BudgetPermissions project = authorization.permissionsFor(Quota.Scope.PROJECT, projectId, auth);
        assertThat(project.canView()).isTrue();
        assertThat(project.canCreateCeiling()).isTrue();
        assertThat(project.canCreateReservation()).isTrue();
        assertThat(project.canCreateServiceBudget()).isFalse();
        assertThat(project.canPauseResume()).isTrue();
        assertThat(project.canDelete()).isTrue();

        BudgetPermissions service = authorization.permissionsFor(Quota.Scope.SERVICE, projectId, auth);
        assertThat(service.canCreateCeiling()).isFalse();
        assertThat(service.canCreateServiceBudget()).isTrue();
        assertThat(service.canPauseResume()).isTrue();
    }

    @Test
    void projectOwnerCanCarveServiceBudgetsButNotProjectCeilings() {
        UUID projectId = UUID.randomUUID();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(projectWithGroup(projectId, null)));

        Authentication auth = auth("proj:" + projectId + ":PROJECT_OWNER");

        BudgetPermissions project = authorization.permissionsFor(Quota.Scope.PROJECT, projectId, auth);
        assertThat(project.canView()).isTrue();
        assertThat(project.canCreateCeiling()).isFalse();
        assertThat(project.canCreateReservation()).isFalse();
        assertThat(project.canPauseResume()).isFalse();
        assertThat(project.canDelete()).isFalse();

        BudgetPermissions service = authorization.permissionsFor(Quota.Scope.SERVICE, projectId, auth);
        assertThat(service.canCreateServiceBudget()).isTrue();
        assertThat(service.canCreateReservation()).isFalse();
        assertThat(service.canPauseResume()).isTrue();
        assertThat(service.canDelete()).isTrue();
    }

    @Test
    void editorCanCarveServiceBudgets() {
        UUID projectId = UUID.randomUUID();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(projectWithGroup(projectId, null)));

        Authentication auth = auth("proj:" + projectId + ":EDITOR");

        BudgetPermissions service = authorization.permissionsFor(Quota.Scope.SERVICE, projectId, auth);
        assertThat(service.canCreateServiceBudget()).isTrue();
        assertThat(service.canPauseResume()).isTrue();
        assertThat(service.canDelete()).isTrue();

        BudgetPermissions project = authorization.permissionsFor(Quota.Scope.PROJECT, projectId, auth);
        assertThat(project.canCreateCeiling()).isFalse();
        assertThat(project.canCreateServiceBudget()).isFalse();
    }

    @Test
    void viewerCannotMutateBudgets() {
        UUID projectId = UUID.randomUUID();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(projectWithGroup(projectId, null)));

        Authentication auth = auth("proj:" + projectId + ":VIEWER");
        BudgetPermissions p = authorization.permissionsFor(Quota.Scope.PROJECT, projectId, auth);

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

    private static Project projectWithGroup(UUID projectId, UUID groupId) {
        Project p = new Project();
        p.setId(projectId);
        p.setGroupId(groupId);
        return p;
    }
}
