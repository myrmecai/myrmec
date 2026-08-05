// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.quota;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.group.Group;
import ai.myrmec.engine.group.GroupRepository;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectRepository;
import ai.myrmec.engine.quota.dto.BudgetTreeNode;
import ai.myrmec.engine.quota.dto.CreateQuotaRequest;
import ai.myrmec.engine.quota.dto.DashboardResponse;
import ai.myrmec.engine.quota.dto.ProjectServiceBudgetsResponse;
import ai.myrmec.engine.spi.quota.QuotaDecision;
import ai.myrmec.engine.spi.quota.QuotaResourceType;
import ai.myrmec.engine.spi.quota.QuotaScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 8j &mdash; end-to-end budget hierarchy test.
 *
 * <p>Creates an org ceiling, project ceiling, and service reservation,
 * then verifies the dashboard tree, service-budget read surface, and
 * that the policy engine blocks at the correct (service) scope.</p>
 */
class BudgetIntegrationTest extends IntegrationTestBase {

    @Autowired private ProjectRepository projectRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private BasicQuotaPolicyEngine engine;

    @Test
    void orgCeilingCascadesToProjectAndService() {
        // Arrange: group + project hierarchy
        Group group = new Group();
        group.setName("Budget Integration Group");
        group = groupRepository.save(group);

        Project project = new Project();
        project.setName("Budget Integration Project");
        project.setGroupId(group.getId());
        project = projectRepository.save(project);

        UUID orgScopeId = UUID.randomUUID();

        // Org ceiling 1000 tokens (DAILY)
        createQuota(CreateQuotaRequest.builder()
                .scopeType("ORG")
                .scopeId(orgScopeId)
                .resourceType("TOKENS")
                .period("DAILY")
                .limitAmount(1_000L)
                .quotaType("CEILING")
                .enforcementMode("BLOCK")
                .build());

        // Project ceiling 800 tokens (tighter than org)
        createQuota(CreateQuotaRequest.builder()
                .scopeType("PROJECT")
                .scopeId(project.getId())
                .resourceType("TOKENS")
                .period("DAILY")
                .limitAmount(800L)
                .quotaType("CEILING")
                .enforcementMode("BLOCK")
                .build());

        // Service reservation 300 tokens under the project
        createQuota(CreateQuotaRequest.builder()
                .scopeType("SERVICE")
                .scopeId(project.getId())
                .resourceType("TOKENS")
                .period("DAILY")
                .limitAmount(300L)
                .quotaType("RESERVATION")
                .enforcementMode("BLOCK")
                .serviceType("WORKFLOW")
                .build());

        // Act / Assert: dashboard tree
        ResponseEntity<DashboardResponse> dashboard = restTemplate.exchange(
                "/api/v1/budgets/dashboard?resourceType=TOKENS&period=DAILY",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                DashboardResponse.class);
        assertThat(dashboard.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dashboard.getBody()).isNotNull();
        List<BudgetTreeNode> tree = dashboard.getBody().getTree();
        assertThat(tree).isNotEmpty();
        BudgetTreeNode orgNode = tree.get(0);
        assertThat(orgNode.getScopeType()).isEqualTo("ORG");
        assertThat(orgNode.getEffectiveLimit()).isEqualTo(1_000L);

        // Act / Assert: project services budget
        ResponseEntity<ProjectServiceBudgetsResponse> services = restTemplate.exchange(
                "/api/v1/budgets/projects/" + project.getId()
                        + "/services?resourceType=TOKENS&period=DAILY",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                ProjectServiceBudgetsResponse.class);
        assertThat(services.getStatusCode()).isEqualTo(HttpStatus.OK);
        ProjectServiceBudgetsResponse body = services.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getProjectId()).isEqualTo(project.getId());
        assertThat(body.getSharedPool().getTotalLimit()).isEqualTo(800L);
        assertThat(body.getSharedPool().getReservedAmount()).isEqualTo(300L);
        assertThat(body.getSharedPool().getSharedAmount()).isEqualTo(500L);
        assertThat(body.getServiceBudgets()).hasSize(1);
        assertThat(body.getServiceBudgets().get(0).getEffectiveLimit()).isEqualTo(300L);

        // Act / Assert: policy engine blocks service consumption at the reservation
        QuotaDecision blocked = engine.check(QuotaScope.SERVICE, project.getId(),
                QuotaResourceType.TOKENS, 350);
        assertThat(blocked.isBlocked()).isTrue();
        assertThat(blocked.getScopeHit()).isEqualTo(QuotaScope.SERVICE);
        assertThat(blocked.getLimitAmount()).isEqualTo(300L);

        // Within project shared pool but above reservation still passes
        QuotaDecision ok = engine.check(QuotaScope.PROJECT, project.getId(),
                QuotaResourceType.TOKENS, 400);
        assertThat(ok.isBlocked()).isFalse();
        assertThat(ok.getLimitAmount()).isEqualTo(800L);

        // Org ceiling is enforced when no project ceiling exists: consume 950 of 1000
        QuotaDecision orgWarning = engine.check(QuotaScope.ORG, orgScopeId,
                QuotaResourceType.TOKENS, 950);
        assertThat(orgWarning.isWarning()).isTrue();
        assertThat(orgWarning.getLimitAmount()).isEqualTo(1_000L);
    }

    private void createQuota(CreateQuotaRequest req) {
        ResponseEntity<Void> created = restTemplate.exchange(
                "/api/v1/admin/quotas",
                HttpMethod.POST,
                new HttpEntity<>(req, adminHeaders()),
                Void.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
