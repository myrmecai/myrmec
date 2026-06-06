package ai.myrmec.engine.conversation;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.conversation.dto.ConversationMessageResponse;
import ai.myrmec.engine.conversation.dto.ConversationResponse;
import ai.myrmec.engine.conversation.dto.CreateConversationRequest;
import ai.myrmec.engine.conversation.dto.PostUserMessageRequest;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.user.AuthenticationProvider;
import ai.myrmec.engine.user.User;
import ai.myrmec.engine.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6c-1 controller integration test — covers the REST surface
 * (create / get / list-messages / post-user-message) and the
 * {@code @conversationAccess} SpEL evaluator (project-level fallback ACL).
 *
 * <p>Not annotated {@code @Transactional} — the embedded HTTP server runs
 * on a separate thread, see {@code AgentRetrievalControllerTest} for the
 * full rationale.</p>
 */
class ConversationControllerTest extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private UserRepository userRepository;

    private UUID seedUser(String suffix) {
        User user = new User();
        user.setEmail("conv-ctrl-" + suffix + "@test.local");
        user.setName("Conv Ctrl " + suffix);
        user.setPasswordHash("$2a$10$dummy");
        user.setProviderCode(AuthenticationProvider.LOCAL_CODE);
        user.setIsActive(true);
        user.setIsSystem(false);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user).getId();
    }

    @Test
    void adminCanCreateConversationAndPostUserMessage() {
        Project project = data.project().named("conv-ctrl-create").create();
        CreateConversationRequest body = new CreateConversationRequest(
                project.getId(), null, "REST smoke", null);

        ResponseEntity<ConversationResponse> created = restTemplate.exchange(
                "/api/v1/conversations",
                HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders()),
                ConversationResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        UUID conversationId = created.getBody().id();
        assertThat(conversationId).isNotNull();
        assertThat(created.getBody().projectId()).isEqualTo(project.getId());
        assertThat(created.getBody().status()).isEqualTo("ACTIVE");

        // Post a USER message.
        PostUserMessageRequest msg = new PostUserMessageRequest("hello world");
        ResponseEntity<ConversationMessageResponse> posted = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/messages",
                HttpMethod.POST,
                new HttpEntity<>(msg, adminHeaders()),
                ConversationMessageResponse.class);

        assertThat(posted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(posted.getBody()).isNotNull();
        assertThat(posted.getBody().role()).isEqualTo("USER");
        assertThat(posted.getBody().content()).isEqualTo("hello world");
        assertThat(posted.getBody().sequenceNo()).isEqualTo(0L);
        assertThat(posted.getBody().authorUserId()).isEqualTo(TEST_ADMIN_ID);

        // Listing returns the same row.
        ResponseEntity<List<ConversationMessageResponse>> listed = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/messages",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                new ParameterizedTypeReference<List<ConversationMessageResponse>>() {});
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody()).hasSize(1);
        assertThat(listed.getBody().get(0).content()).isEqualTo("hello world");
    }

    @Test
    void unauthenticatedCallIsRejected() {
        Project project = data.project().named("conv-ctrl-noauth").create();
        CreateConversationRequest body = new CreateConversationRequest(
                project.getId(), null, "should be rejected", null);

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/conversations",
                HttpMethod.POST,
                new HttpEntity<>(body),
                String.class);

        assertThat(resp.getStatusCode().value()).isIn(401, 403);
    }

    @Test
    void projectEditorCanCreateConversationViaFallbackAcl() {
        Project project = data.project().named("conv-ctrl-editor").create();
        UUID someUser = seedUser("editor-" + UUID.randomUUID().toString().substring(0, 8));

        CreateConversationRequest body = new CreateConversationRequest(
                project.getId(), null, "editor session", null);

        ResponseEntity<ConversationResponse> resp = restTemplate.exchange(
                "/api/v1/conversations",
                HttpMethod.POST,
                new HttpEntity<>(body, userHeaders(someUser, project.getId())),
                ConversationResponse.class);

        // userHeaders mints a proj:<id>:EDITOR claim → projectAccess.canView passes
        // (EDITOR implies VIEWER via UserRole.Role.impliedRoles()).
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().createdBy()).isEqualTo(someUser);
    }

    @Test
    void outsiderCannotAccessConversation() {
        // Admin creates the conversation under one project.
        Project project = data.project().named("conv-ctrl-outsider").create();
        CreateConversationRequest body = new CreateConversationRequest(
                project.getId(), null, "private", null);
        ResponseEntity<ConversationResponse> created = restTemplate.exchange(
                "/api/v1/conversations",
                HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders()),
                ConversationResponse.class);
        UUID conversationId = created.getBody().id();

        // Different user holds EDITOR on a *different* project — should not see this conv.
        UUID outsider = seedUser("outsider-" + UUID.randomUUID().toString().substring(0, 8));
        UUID otherProject = UUID.randomUUID();
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/messages",
                HttpMethod.GET,
                new HttpEntity<>(userHeaders(outsider, otherProject)),
                String.class);

        assertThat(resp.getStatusCode().value()).isIn(401, 403);
    }
}
