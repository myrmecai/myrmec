// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.quota;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.group.Group;
import ai.myrmec.engine.group.GroupRepository;
import ai.myrmec.engine.team.Team;
import ai.myrmec.engine.team.TeamRepository;
import ai.myrmec.engine.quota.dto.DashboardResponse;
import ai.myrmec.engine.quota.dto.EffectiveQuotasResponse;
import ai.myrmec.engine.quota.dto.TeamServiceBudgetsResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 8g &mdash; read-only budget dashboard endpoints.
 */
class BudgetControllerTest extends IntegrationTestBase {

    @Autowired private TeamRepository TeamRepository;
    @Autowired private GroupRepository groupRepository;

    @Test
    void canFetchProjectServicesBudget() {
        Group group = new Group();
        group.setName("Budget Controller Group");
        group = groupRepository.save(group);

        team team = new team();
        team.setName("Budget Controller team");
        team.setGroupId(group.getId());
        team = TeamRepository.save(team);

        String url = "/api/v1/budgets/teams/" + team.getId()
                + "/services?resourceType=COST_USD_CENTS&period=MONTHLY_CALENDAR";
        ResponseEntity<TeamServiceBudgetsResponse> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(adminHeaders()),
                TeamServiceBudgetsResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getTeamId()).isEqualTo(team.getId());
        assertThat(resp.getBody().getResourceType()).isEqualTo("COST_USD_CENTS");
        assertThat(resp.getBody().getPeriod()).isEqualTo("MONTHLY_CALENDAR");
        assertThat(resp.getBody().getSharedPool()).isNotNull();
    }

    @Test
    void canFetchDashboard() {
        // Create an org-level quota so the dashboard tree has at least one node.
        quotaRepository.deleteAllInBatch();
        Quota orgQuota = new Quota();
        orgQuota.setScopeType(Quota.Scope.ORG);
        orgQuota.setScopeId(UUID.randomUUID());  // org scope id is synthetic in Community
        orgQuota.setResourceType(Quota.ResourceType.COST_USD_CENTS);
        orgQuota.setPeriod(Quota.Period.MONTHLY_CALENDAR);
        orgQuota.setLimitAmount(50_000L);
        orgQuota.setEnforced(true);
        orgQuota.setQuotaType(QuotaType.CEILING);
        orgQuota.setEnforcementMode(EnforcementMode.BLOCK);
        quotaRepository.save(orgQuota);

        String url = "/api/v1/budgets/dashboard?resourceType=COST_USD_CENTS&period=MONTHLY_CALENDAR";
        ResponseEntity<DashboardResponse> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(adminHeaders()),
                DashboardResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getTree()).isNotNull();
        assertThat(resp.getBody().getTree()).isNotEmpty();
    }

    @Test
    void canFetchProjectEffectiveQuotas() {
        Group group = new Group();
        group.setName("Budget Effective Quotas Group");
        group = groupRepository.save(group);

        team team = new team();
        team.setName("Budget Effective Quotas team");
        team.setGroupId(group.getId());
        team = TeamRepository.save(team);

        String url = "/api/v1/budgets/teams/" + team.getId()
                + "/effective-quotas?resourceType=COST_USD_CENTS&period=MONTHLY_CALENDAR";
        ResponseEntity<EffectiveQuotasResponse> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(adminHeaders()),
                EffectiveQuotasResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getQuotas()).isNotNull();
        assertThat(resp.getBody().getQuotas()).isNotEmpty();
    }

    @Test
    void canFetchGroupEffectiveQuotas() {
        Group group = new Group();
        group.setName("Budget Group Effective Quotas");
        group = groupRepository.save(group);

        String url = "/api/v1/budgets/groups/" + group.getId()
                + "/effective-quotas?resourceType=COST_USD_CENTS&period=MONTHLY_CALENDAR";
        ResponseEntity<EffectiveQuotasResponse> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(adminHeaders()),
                EffectiveQuotasResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getQuotas()).isNotNull();
    }
}
