package ai.myrmec.engine.external;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.assistant.Assistant;
import ai.myrmec.engine.assistant.AssistantGrant;
import ai.myrmec.engine.assistant.AssistantGrantRepository;
import ai.myrmec.engine.assistant.AssistantRepository;
import ai.myrmec.engine.assistant.AssistantService;
import ai.myrmec.engine.assistant.AssistantVersion;
import ai.myrmec.engine.assistant.AssistantVersionRepository;
import ai.myrmec.engine.assistant.AssistantVersionService;
import ai.myrmec.engine.external.dto.ExternalAssistantResponse;
import ai.myrmec.engine.external.dto.ExternalConversationResponse;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.serviceaccount.ServiceAccount;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end HTTP test of the External-API resource-server chain (#95): proves
 * that {@code ExternalApiSecurityConfig} + {@link ai.myrmec.engine._system.security.ServiceAccountJwtAuthenticationConverter}
 * + {@link ai.myrmec.engine._system.security.CurrentServiceAccountArgumentResolver}
 * + the double gate wire together over real HTTP.
 *
 * <p>The chain is enabled via {@code myrmec.external-api.enabled=true} and a
 * {@link StubJwtDecoder} stands in for Keycloak: the bearer token string is
 * treated as the caller's {@code azp} client id, so a request authenticates as
 * whichever service account owns that Keycloak client id.</p>
 */
@TestPropertySource(properties = {
        "myrmec.external-api.enabled=true",
        // Let the StubDecoderConfig bean (same name) replace the production
        // Keycloak decoder so the chain can be exercised without a real IdP.
        "spring.main.allow-bean-definition-overriding=true"
})
class ExternalApiChainIT extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private AssistantService assistantService;

    @Autowired
    private AssistantVersionService versionService;

    @Autowired
    private AssistantRepository assistantRepository;

    @Autowired
    private AssistantVersionRepository assistantVersionRepository;

    @Autowired
    private AssistantGrantRepository assistantGrantRepository;

    /**
     * The shared {@code cleanupTestData()} in the base class clears projects
     * but not assistant tables. Because this is a non-transactional HTTP test,
     * committed assistants would otherwise survive into the next test and
     * collide with the base cleanup's {@code projectRepository.deleteAll()}
     * (assistants still reference the project). Clear them after each test.
     */
    @AfterEach
    void clearAssistantData() {
        // Conversations reference assistant_version; clear them
        // first so the assistant tables can be removed without FK violations.
        conversationMessageRepository.deleteAllInBatch();
        conversationParticipantRepository.deleteAllInBatch();
        conversationRepository.deleteAllInBatch();
        assistantGrantRepository.deleteAllInBatch();
        assistantVersionRepository.deleteAllInBatch();
        assistantRepository.deleteAllInBatch();
    }

    /**
     * Stand-in for the Keycloak {@code JwtDecoder}: maps the raw bearer token
     * straight onto the {@code azp} claim so tests pick an identity by sending
     * the service account's client id as the token. Backs off the production
     * decoder via {@code @ConditionalOnMissingBean(JwtDecoder.class)}.
     */
    @TestConfiguration
    static class StubDecoderConfig {
        @Bean
        JwtDecoder externalApiJwtDecoder() {
            return token -> {
                Instant now = Instant.now();
                return Jwt.withTokenValue(token)
                        .header("alg", "none")
                        .claim("azp", token)
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(3600))
                        .build();
            };
        }
    }

    @Test
    void discoversUsableAssistantOverHttp() {
        Fixture f = fixture("chain-disco");

        ResponseEntity<ExternalAssistantResponse[]> response = restTemplate.exchange(
                "/api/v1/external/assistants",
                HttpMethod.GET,
                new HttpEntity<>(bearer(f.clientId)),
                ExternalAssistantResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody()[0].id()).isEqualTo(f.assistantId);
    }

    @Test
    void startsConversationOverHttp() {
        Fixture f = fixture("chain-start");

        HttpHeaders headers = bearer(f.clientId);
        headers.set("X-Myrmec-End-User-Ref", "end-user-7");
        ResponseEntity<ExternalConversationResponse> response = restTemplate.exchange(
                "/api/v1/external/assistants/" + f.assistantId + "/conversations",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("firstMessage", "hello"), headers),
                ExternalConversationResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().externalUserRef()).isEqualTo("end-user-7");
        assertThat(response.getBody().assistantId()).isEqualTo(f.assistantId);
    }

    @Test
    void rejectsMissingTokenWith401() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/external/assistants",
                HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsUnknownClientWith401() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/external/assistants",
                HttpMethod.GET,
                new HttpEntity<>(bearer("no-such-client-" + UUID.randomUUID())),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void hidesUngrantedAssistantWith404() {
        Fixture f = fixture("chain-gate");
        // A second assistant the service account has no USE grant on.
        UUID ungranted = publishedAssistant(f.project, f.profile, "Ungranted");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/external/assistants/" + ungranted,
                HttpMethod.GET,
                new HttpEntity<>(bearer(f.clientId)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void requiresEndUserRefHeaderWith400() {
        Fixture f = fixture("chain-noref");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/external/assistants/" + f.assistantId + "/conversations",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("firstMessage", "hi"), bearer(f.clientId)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ==================== Fixtures ====================

    private record Fixture(Project project, AgentProfile profile,
                           UUID assistantId, String clientId) {
    }

    private Fixture fixture(String slug) {
        Project project = data.project().named(slug).create();
        AgentProfile profile = data.agentProfile().named(slug + "-profile").create();
        ServiceAccount sa = serviceAccount(project);
        UUID assistantId = publishedAssistant(project, profile, slug + "-assistant");
        assistantService.addGrant(assistantId, AssistantGrant.PrincipalType.SERVICE_ACCOUNT,
                sa.getId().toString(), AssistantGrant.Permission.USE, TEST_ADMIN_ID);
        return new Fixture(project, profile, assistantId, sa.getKeycloakClientId());
    }

    private UUID publishedAssistant(Project project, AgentProfile profile, String name) {
        Assistant assistant = assistantService.createAssistant(
                project.getId(), name, "desc", profile.getId(), TEST_ADMIN_ID);
        AssistantVersion draft = versionService.getOpenDraft(assistant.getId());
        draft.setUsableVia(new ArrayList<>(List.of("WEB_UI", "EXTERNAL_API")));
        versionService.saveDraft(draft);
        versionService.publish(assistant.getId(), TEST_ADMIN_ID);
        return assistant.getId();
    }

    private ServiceAccount serviceAccount(Project project) {
        ServiceAccount sa = new ServiceAccount();
        sa.setProjectId(project.getId());
        sa.setName("chain-sa-" + UUID.randomUUID());
        sa.setKeycloakClientId("client-" + UUID.randomUUID());
        sa.setEnabled(true);
        return serviceAccountRepository.save(sa);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
