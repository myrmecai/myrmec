// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.quota;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.group.Group;
import ai.myrmec.engine.group.GroupRepository;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectRepository;
import ai.myrmec.engine.quota.dto.ProjectServiceBudgetsResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 8g &mdash; read-only budget dashboard endpoints.
 */
class BudgetControllerTest extends IntegrationTestBase {

    @Autowired private ProjectRepository projectRepository;
    @Autowired private GroupRepository groupRepository;

    @Test
    void canFetchProjectServicesBudget() {
        Group group = new Group();
        group.setName("Budget Controller Group");
        group = groupRepository.save(group);

        Project project = new Project();
        project.setName("Budget Controller Project");
        project.setGroupId(group.getId());
        project = projectRepository.save(project);

        String url = "/api/v1/budgets/projects/" + project.getId()
                + "/services?resourceType=COST_USD_CENTS&period=MONTHLY_CALENDAR";
        ResponseEntity<ProjectServiceBudgetsResponse> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(adminHeaders()),
                ProjectServiceBudgetsResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getProjectId()).isEqualTo(project.getId());
        assertThat(resp.getBody().getResourceType()).isEqualTo("COST_USD_CENTS");
        assertThat(resp.getBody().getPeriod()).isEqualTo("MONTHLY_CALENDAR");
        assertThat(resp.getBody().getSharedPool()).isNotNull();
    }
}
