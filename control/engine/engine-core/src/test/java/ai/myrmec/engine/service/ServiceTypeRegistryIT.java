package ai.myrmec.engine.service;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.project.ProjectService;
import ai.myrmec.engine.project.dto.CreateProjectRequest;
import ai.myrmec.engine.service.dto.ServiceTypeResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #78 — the platform service-type registry backing the read-only
 * Platform → Service Types page.
 */
@Transactional
class ServiceTypeRegistryIT extends IntegrationTestBase {

    @Autowired
    private ServiceTypeRegistry registry;

    @Autowired
    private ProjectService projectService;

    private Map<String, ServiceTypeResponse> byCode() {
        return registry.list().stream()
                .collect(Collectors.toMap(ServiceTypeResponse::getCode, Function.identity()));
    }

    @Test
    void listsEveryShippedServiceTypeWithMetadata() {
        Map<String, ServiceTypeResponse> map = byCode();

        assertThat(map.keySet()).containsExactlyInAnyOrder("WORKFLOW", "CONVERSATIONAL");
        ServiceTypeResponse workflow = map.get("WORKFLOW");
        assertThat(workflow.getDisplayName()).isEqualTo("Workflow");
        assertThat(workflow.getDescription()).isNotBlank();
        assertThat(workflow.getIcon()).isEqualTo("workflow");
        assertThat(workflow.isEnabled()).isTrue();
    }

    @Test
    void countsProjectsThatAllowEachType() {
        long workflowBefore = byCode().get("WORKFLOW").getProjectEnabledCount();
        long conversationalBefore = byCode().get("CONVERSATIONAL").getProjectEnabledCount();

        // Both types -> increments both counts.
        projectService.create(both("svc-reg-both-" + UUID.randomUUID()));
        // Workflow only -> increments WORKFLOW only.
        projectService.create(onlyWorkflow("svc-reg-wf-" + UUID.randomUUID()));

        Map<String, ServiceTypeResponse> after = byCode();
        assertThat(after.get("WORKFLOW").getProjectEnabledCount()).isEqualTo(workflowBefore + 2);
        assertThat(after.get("CONVERSATIONAL").getProjectEnabledCount()).isEqualTo(conversationalBefore + 1);
    }

    private CreateProjectRequest both(String name) {
        CreateProjectRequest r = new CreateProjectRequest();
        r.setName(name);
        r.setAllowedServiceTypes(List.of("WORKFLOW", "CONVERSATIONAL"));
        return r;
    }

    private CreateProjectRequest onlyWorkflow(String name) {
        CreateProjectRequest r = new CreateProjectRequest();
        r.setName(name);
        r.setAllowedServiceTypes(List.of("WORKFLOW"));
        return r;
    }
}
