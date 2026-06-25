package ai.myrmec.engine.mywork;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.assistant.Assistant;
import ai.myrmec.engine.assistant.AssistantGrantRepository;
import ai.myrmec.engine.assistant.AssistantRepository;
import ai.myrmec.engine.assistant.AssistantService;
import ai.myrmec.engine.assistant.AssistantVersionRepository;
import ai.myrmec.engine.mywork.dto.MyArchivedRow;
import ai.myrmec.engine.mywork.dto.MyAssistantRow;
import ai.myrmec.engine.mywork.dto.MyWorkSummaryResponse;
import ai.myrmec.engine.mywork.dto.MyWorkflowRow;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.user.User;
import ai.myrmec.engine.workflow.Workflow;
import ai.myrmec.engine.workflow.WorkflowRepository;
import ai.myrmec.engine.workflow.WorkflowStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP smoke test for {@link MyWorkController} (UC-013) — validates bean wiring,
 * project-scope resolution, the four read-only tab roll-ups, and the
 * summary counters / first-run hints over the REST surface.
 */
class MyWorkControllerIT extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private AssistantRepository assistantRepository;

    @Autowired
    private AssistantVersionRepository assistantVersionRepository;

    @Autowired
    private AssistantGrantRepository assistantGrantRepository;

    @Autowired
    private AssistantService assistantService;

    @AfterEach
    void cleanupMyWork() {
        // HTTP/service writes commit in their own transactions; clean the
        // service tables (FK-safe order) before the base cleanup drops projects.
        workflowRepository.deleteAllInBatch();
        assistantGrantRepository.deleteAllInBatch();
        List<Assistant> all = assistantRepository.findAll();
        all.forEach(a -> a.setCurrentVersionId(null));
        assistantRepository.saveAll(all);
        assistantVersionRepository.deleteAllInBatch();
        assistantRepository.deleteAllInBatch();
    }

    @Test
    void summaryAndTabsRollUpScopedServices() {
        Project project = data.project().named("my-work-it").create();
        AgentProfile profile = data.agentProfile().named("my-work-it-profile").create();
        User admin = userRepository.findById(TEST_ADMIN_ID).orElseThrow();

        // One active workflow definition.
        Workflow workflow = new Workflow();
        workflow.setProject(project);
        workflow.setName("Nightly Import");
        workflow.setDescription("imports dealers");
        workflow.setSteps(List.of());
        workflow.setStatus(WorkflowStatus.PUBLISHED);
        workflow.setCreatedBy(admin);
        workflowRepository.save(workflow);

        // One live assistant, plus one archived assistant.
        Assistant live = assistantService.createAssistant(
                project.getId(), "Support Bot", "answers questions", profile.getId(), TEST_ADMIN_ID);
        Assistant archived = assistantService.createAssistant(
                project.getId(), "Retired Bot", "old", profile.getId(), TEST_ADMIN_ID);
        assistantService.archive(archived.getId());

        HttpHeaders headers = ownerHeaders(project.getId());
        String scope = "?projectIds=" + project.getId();

        // ---- summary
        ResponseEntity<MyWorkSummaryResponse> summary = restTemplate.exchange(
                "/api/v1/my-work/summary" + scope, HttpMethod.GET,
                new HttpEntity<>(headers), MyWorkSummaryResponse.class);
        assertThat(summary.getStatusCode()).isEqualTo(HttpStatus.OK);
        MyWorkSummaryResponse s = summary.getBody();
        assertThat(s).isNotNull();
        assertThat(s.workflows().created()).isEqualTo(1);
        assertThat(s.conversations().created()).isEqualTo(1); // archived excluded
        assertThat(s.archivedCount()).isEqualTo(1);
        assertThat(s.approvalsPending()).isZero();
        assertThat(s.firstRun()).isFalse();
        assertThat(s.canCreateWorkflow()).isTrue();
        assertThat(s.canCreateConversation()).isTrue();

        // ---- workflows tab
        ResponseEntity<List<MyWorkflowRow>> workflows = restTemplate.exchange(
                "/api/v1/my-work/workflows" + scope, HttpMethod.GET,
                new HttpEntity<>(headers), new ParameterizedTypeReference<>() {
                });
        assertThat(workflows.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(workflows.getBody()).extracting(MyWorkflowRow::name).containsExactly("Nightly Import");
        assertThat(workflows.getBody().get(0).projectName()).isEqualTo(project.getName());

        // ---- conversations tab (active assistants only)
        ResponseEntity<List<MyAssistantRow>> conversations = restTemplate.exchange(
                "/api/v1/my-work/conversations" + scope, HttpMethod.GET,
                new HttpEntity<>(headers), new ParameterizedTypeReference<>() {
                });
        assertThat(conversations.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(conversations.getBody()).extracting(MyAssistantRow::name).containsExactly("Support Bot");

        // ---- archived tab
        ResponseEntity<List<MyArchivedRow>> archivedTab = restTemplate.exchange(
                "/api/v1/my-work/archived" + scope, HttpMethod.GET,
                new HttpEntity<>(headers), new ParameterizedTypeReference<>() {
                });
        assertThat(archivedTab.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(archivedTab.getBody()).extracting(MyArchivedRow::name).containsExactly("Retired Bot");
        assertThat(archivedTab.getBody().get(0).type()).isEqualTo(MyArchivedRow.Type.ASSISTANT);

        // ---- approvals tab (none pending)
        ResponseEntity<List<Object>> approvals = restTemplate.exchange(
                "/api/v1/my-work/approvals" + scope, HttpMethod.GET,
                new HttpEntity<>(headers), new ParameterizedTypeReference<>() {
                });
        assertThat(approvals.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approvals.getBody()).isEmpty();
    }

    @Test
    void firstRunWhenScopeHasNoServices() {
        Project empty = data.project().named("my-work-empty").create();
        HttpHeaders headers = adminHeaders();

        ResponseEntity<MyWorkSummaryResponse> summary = restTemplate.exchange(
                "/api/v1/my-work/summary?projectIds=" + empty.getId(), HttpMethod.GET,
                new HttpEntity<>(headers), MyWorkSummaryResponse.class);
        assertThat(summary.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(summary.getBody()).isNotNull();
        assertThat(summary.getBody().firstRun()).isTrue();
        assertThat(summary.getBody().workflows().created()).isZero();
        assertThat(summary.getBody().conversations().created()).isZero();
    }

    @Test
    void strangerSeesEmptyScope() {
        Project project = data.project().named("my-work-private").create();
        AgentProfile profile = data.agentProfile().named("my-work-private-profile").create();
        assistantService.createAssistant(
                project.getId(), "Hidden Bot", "secret", profile.getId(), TEST_ADMIN_ID);

        // A real, active user whose token only grants EDITOR on a different
        // project must resolve to an empty My Work scope (no 403, just no rows).
        HttpHeaders strangerHeaders = userHeaders(TEST_ADMIN_ID, UUID.randomUUID());

        ResponseEntity<List<MyAssistantRow>> conversations = restTemplate.exchange(
                "/api/v1/my-work/conversations?projectIds=" + project.getId(), HttpMethod.GET,
                new HttpEntity<>(strangerHeaders), new ParameterizedTypeReference<>() {
                });
        assertThat(conversations.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(conversations.getBody()).isEmpty();
    }

    /** Owner (PROJECT_OWNER ⇒ EDITOR) token on a single project, as a real active user. */
    private HttpHeaders ownerHeaders(UUID projectId) {
        String token = jwtTokenProvider.generateUserAccessToken(
                TEST_ADMIN_ID, "Owner User", "owner@test.local",
                List.of("proj:" + projectId + ":PROJECT_OWNER"));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("Content-Type", "application/json");
        return headers;
    }
}
