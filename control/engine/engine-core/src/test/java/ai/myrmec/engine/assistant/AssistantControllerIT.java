package ai.myrmec.engine.assistant;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.assistant.dto.AssistantResponse;
import ai.myrmec.engine.assistant.dto.AssistantVersionResponse;
import ai.myrmec.engine.assistant.dto.CreateAssistantRequest;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
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
 * HTTP smoke test for {@link AssistantController} (#92) — validates bean wiring,
 * {@code assistantAccess} / {@code projectAccess} SpEL gates, JSON
 * serialization, and the create → publish path end-to-end over the REST surface.
 * Lifecycle edge cases are covered at the service tier by {@code AssistantLifecycleIT}.
 */
class AssistantControllerIT extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private AssistantRepository assistantRepository;

    @Autowired
    private AssistantVersionRepository versionRepository;

    @Autowired
    private AssistantGrantRepository grantRepository;

    @AfterEach
    void cleanupAssistants() {
        // HTTP calls commit in their own transactions, so clean the assistant
        // tables here (FK-safe order) before the base cleanup deletes projects.
        grantRepository.deleteAllInBatch();
        List<Assistant> all = assistantRepository.findAll();
        all.forEach(a -> a.setCurrentVersionId(null));
        assistantRepository.saveAll(all);
        versionRepository.deleteAllInBatch();
        assistantRepository.deleteAllInBatch();
    }

    @Test
    void createListAndPublishOverHttp() {
        Project project = data.project().named("assistant-http").create();
        AgentProfile profile = data.agentProfile().named("assistant-http-profile").create();
        HttpHeaders headers = ownerHeaders(TEST_ADMIN_ID, project.getId());

        // Create
        CreateAssistantRequest createReq = new CreateAssistantRequest(
                project.getId(), "HTTP Assistant", "via REST", profile.getId());
        ResponseEntity<AssistantResponse> created = restTemplate.exchange(
                "/api/v1/assistants", HttpMethod.POST,
                new HttpEntity<>(createReq, headers), AssistantResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        AssistantResponse assistant = created.getBody();
        assertThat(assistant).isNotNull();
        assertThat(assistant.currentVersionId()).isNull();

        // List
        ResponseEntity<List<AssistantResponse>> listed = restTemplate.exchange(
                "/api/v1/assistants?projectId=" + project.getId(), HttpMethod.GET,
                new HttpEntity<>(headers), new ParameterizedTypeReference<>() {
                });
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody()).extracting(AssistantResponse::id).contains(assistant.id());

        // Publish the seeded draft
        ResponseEntity<AssistantVersionResponse> published = restTemplate.exchange(
                "/api/v1/assistants/" + assistant.id() + "/publish", HttpMethod.POST,
                new HttpEntity<>(headers), AssistantVersionResponse.class);
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(published.getBody()).isNotNull();
        assertThat(published.getBody().status()).isEqualTo("PUBLISHED");
        assertThat(published.getBody().versionNumber()).isEqualTo("1.0");
    }

    @Test
    void readRejectedWithoutProjectAccess() {
        Project project = data.project().named("assistant-http-deny").create();
        AgentProfile profile = data.agentProfile().named("assistant-http-deny-profile").create();
        Assistant assistant = assistantService.createAssistant(
                project.getId(), "Seed " + UUID.randomUUID(), "desc", profile.getId(), TEST_ADMIN_ID);

        // A real, active user whose token only grants access to a different
        // project must not read this assistant (authorization denial, 403).
        UUID strangerId = createActiveUser("stranger-" + UUID.randomUUID() + "@test.local");
        HttpHeaders strangerHeaders = ownerHeaders(strangerId, UUID.randomUUID());
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/assistants/" + assistant.getId(), HttpMethod.GET,
                new HttpEntity<>(strangerHeaders), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ==================== Helpers ====================

    @Autowired
    private AssistantService assistantService;

    private UUID createActiveUser(String email) {
        ai.myrmec.engine.user.User user = new ai.myrmec.engine.user.User();
        user.setEmail(email);
        user.setName("Stranger");
        user.setPasswordHash("$2a$10$dummy");
        user.setProviderCode(ai.myrmec.engine.user.AuthenticationProvider.LOCAL_CODE);
        user.setIsActive(true);
        user.setIsSystem(false);
        user.setCreatedAt(java.time.Instant.now());
        user.setUpdatedAt(java.time.Instant.now());
        return userRepository.save(user).getId();
    }

    private HttpHeaders ownerHeaders(UUID userId, UUID projectId) {
        String token = jwtTokenProvider.generateUserAccessToken(
                userId, "Owner User", "owner@test.local",
                List.of("proj:" + projectId + ":PROJECT_OWNER"));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("Content-Type", "application/json");
        return headers;
    }
}
