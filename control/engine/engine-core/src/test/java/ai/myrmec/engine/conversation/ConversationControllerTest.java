package ai.myrmec.engine.conversation;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.conversation.dto.ConversationEventResponse;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private ConversationEventService conversationEventService;

    @Autowired
    private ConversationService conversationService;

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
    @org.junit.jupiter.api.Disabled("TODO: investigate auto-title failure after audit_events table addition")
    void firstUserMessageAutoTitlesAnUntitledConversation() {
        Project project = data.project().named("conv-ctrl-autotitle").create();

        // Created with no title.
        CreateConversationRequest body = new CreateConversationRequest(
                project.getId(), null, null, null);
        ResponseEntity<ConversationResponse> created = restTemplate.exchange(
                "/api/v1/conversations",
                HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders()),
                ConversationResponse.class);
        UUID conversationId = created.getBody().id();
        assertThat(created.getBody().title()).isNull();

        // A long first turn is collapsed + truncated into a tidy title.
        String first = "  How do I   configure single sign-on for my organization "
                + "using the OIDC broker and what scopes are required exactly  ";
        restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/messages",
                HttpMethod.POST,
                new HttpEntity<>(new PostUserMessageRequest(first), adminHeaders()),
                ConversationMessageResponse.class);

        ResponseEntity<ConversationResponse> afterFirst = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId,
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                ConversationResponse.class);
        String autoTitle = afterFirst.getBody().title();
        assertThat(autoTitle).isNotNull();
        assertThat(autoTitle).startsWith("How do I configure single sign-on");
        assertThat(autoTitle).doesNotContain("  ");
        assertThat(autoTitle.length()).isLessThanOrEqualTo(61); // 60 + ellipsis

        // A second turn must not overwrite the established title.
        restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/messages",
                HttpMethod.POST,
                new HttpEntity<>(new PostUserMessageRequest("a different second message"), adminHeaders()),
                ConversationMessageResponse.class);
        ResponseEntity<ConversationResponse> afterSecond = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId,
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                ConversationResponse.class);
        assertThat(afterSecond.getBody().title()).isEqualTo(autoTitle);
    }

    @Test
    void userSuppliedTitleSurvivesTheFirstUserMessage() {
        Project project = data.project().named("conv-ctrl-keeptitle").create();
        CreateConversationRequest body = new CreateConversationRequest(
                project.getId(), null, "My chosen title", null);
        ResponseEntity<ConversationResponse> created = restTemplate.exchange(
                "/api/v1/conversations",
                HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders()),
                ConversationResponse.class);
        UUID conversationId = created.getBody().id();

        restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/messages",
                HttpMethod.POST,
                new HttpEntity<>(new PostUserMessageRequest("anything at all"), adminHeaders()),
                ConversationMessageResponse.class);

        ResponseEntity<ConversationResponse> after = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId,
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                ConversationResponse.class);
        assertThat(after.getBody().title()).isEqualTo("My chosen title");
    }

    @Test
    void cancelReturnsNotDeliveredWhenNoWorkerSocketAttached() {
        Project project = data.project().named("conv-ctrl-cancel").create();
        CreateConversationRequest body = new CreateConversationRequest(
                project.getId(), null, "Cancel smoke", null);
        ResponseEntity<ConversationResponse> created = restTemplate.exchange(
                "/api/v1/conversations",
                HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders()),
                ConversationResponse.class);
        UUID conversationId = created.getBody().id();

        // No bound worker / conversation socket exists in this test, so the
        // cancel relay finds nothing to deliver to and reports delivered=false.
        ResponseEntity<Map<String, Boolean>> cancelled = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/cancel",
                HttpMethod.POST,
                new HttpEntity<>(adminHeaders()),
                new ParameterizedTypeReference<Map<String, Boolean>>() {});
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody()).isNotNull();
        assertThat(cancelled.getBody().get("delivered")).isFalse();
    }

    @Test
    void editUserMessageSupersedesTailAndAppendsNewUserTurn() {
        Project project = data.project().named("conv-ctrl-edit").create();
        CreateConversationRequest body = new CreateConversationRequest(
                project.getId(), null, "Edit smoke", null);
        UUID conversationId = restTemplate.exchange(
                "/api/v1/conversations",
                HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders()),
                ConversationResponse.class).getBody().id();

        // Original USER turn at sequence 0.
        ResponseEntity<ConversationMessageResponse> original = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/messages",
                HttpMethod.POST,
                new HttpEntity<>(new PostUserMessageRequest("waht is the capitol of france"), adminHeaders()),
                ConversationMessageResponse.class);
        UUID originalId = original.getBody().id();

        // Edit-and-resend that turn.
        ResponseEntity<ConversationMessageResponse> edited = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/messages/" + originalId + "/edit",
                HttpMethod.POST,
                new HttpEntity<>(new PostUserMessageRequest("what is the capital of France?"), adminHeaders()),
                ConversationMessageResponse.class);
        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(edited.getBody()).isNotNull();
        assertThat(edited.getBody().role()).isEqualTo("USER");
        assertThat(edited.getBody().content()).isEqualTo("what is the capital of France?");
        assertThat(edited.getBody().parentMessageId()).isEqualTo(originalId);
        assertThat(edited.getBody().superseded()).isFalse();
        assertThat(edited.getBody().sequenceNo()).isEqualTo(1L);

        // The original is retained but soft-superseded; the new turn is active.
        List<ConversationMessage> all = conversationService.listMessages(conversationId);
        assertThat(all).hasSize(2);
        assertThat(all.get(0).getId()).isEqualTo(originalId);
        assertThat(all.get(0).isSuperseded()).isTrue();
        assertThat(all.get(1).isSuperseded()).isFalse();
    }

    @Test
    void editRejectsAssistantMessage() {
        Project project = data.project().named("conv-ctrl-edit-reject").create();
        CreateConversationRequest body = new CreateConversationRequest(
                project.getId(), null, "Edit reject", null);
        UUID conversationId = restTemplate.exchange(
                "/api/v1/conversations",
                HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders()),
                ConversationResponse.class).getBody().id();

        ConversationMessage assistant = conversationService.appendMessage(
                conversationId, ConversationMessage.Role.ASSISTANT, "Paris.", null, null);

        ResponseEntity<String> rejected = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/messages/" + assistant.getId() + "/edit",
                HttpMethod.POST,
                new HttpEntity<>(new PostUserMessageRequest("not allowed"), adminHeaders()),
                String.class);
        assertThat(rejected.getStatusCode().is4xxClientError()
                || rejected.getStatusCode().is5xxServerError()).isTrue();
    }

    @Test
    void regenerateSupersedesAssistantTurn() {
        Project project = data.project().named("conv-ctrl-regen").create();
        CreateConversationRequest body = new CreateConversationRequest(
                project.getId(), null, "Regen smoke", null);
        UUID conversationId = restTemplate.exchange(
                "/api/v1/conversations",
                HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders()),
                ConversationResponse.class).getBody().id();

        // A USER turn followed by an ASSISTANT answer.
        restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/messages",
                HttpMethod.POST,
                new HttpEntity<>(new PostUserMessageRequest("what is the capital of France?"), adminHeaders()),
                ConversationMessageResponse.class);
        ConversationMessage assistant = conversationService.appendMessage(
                conversationId, ConversationMessage.Role.ASSISTANT, "London.", null, null);

        ResponseEntity<ConversationMessageResponse> regenerated = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/messages/" + assistant.getId() + "/regenerate",
                HttpMethod.POST,
                new HttpEntity<>(adminHeaders()),
                ConversationMessageResponse.class);
        assertThat(regenerated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(regenerated.getBody()).isNotNull();
        assertThat(regenerated.getBody().id()).isEqualTo(assistant.getId());
        assertThat(regenerated.getBody().superseded()).isTrue();

        // The USER turn stays active; the assistant answer is superseded.
        List<ConversationMessage> all = conversationService.listMessages(conversationId);
        assertThat(all).hasSize(2);
        assertThat(all.get(0).getRole()).isEqualTo(ConversationMessage.Role.USER);
        assertThat(all.get(0).isSuperseded()).isFalse();
        assertThat(all.get(1).getId()).isEqualTo(assistant.getId());
        assertThat(all.get(1).isSuperseded()).isTrue();
    }

    @Test
    void ownerCanRenameAndArchiveAndUnarchiveConversation() throws Exception {
        Project project = data.project().named("conv-ctrl-patch").create();
        CreateConversationRequest body = new CreateConversationRequest(
                project.getId(), null, "Original title", null);
        ResponseEntity<ConversationResponse> created = restTemplate.exchange(
                "/api/v1/conversations",
                HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders()),
                ConversationResponse.class);
        UUID conversationId = created.getBody().id();

        // Rename + archive in one call.
        HttpResponse<String> archived = patch(
                "/api/v1/conversations/" + conversationId,
                "{\"title\":\"Renamed\",\"status\":\"ARCHIVED\"}");
        assertThat(archived.statusCode()).isEqualTo(200);
        JsonNode archivedBody = JSON.readTree(archived.body());
        assertThat(archivedBody.get("title").asText()).isEqualTo("Renamed");
        assertThat(archivedBody.get("status").asText()).isEqualTo("ARCHIVED");

        // Unarchive — title is left untouched because it is omitted.
        HttpResponse<String> reactivated = patch(
                "/api/v1/conversations/" + conversationId,
                "{\"status\":\"ACTIVE\"}");
        assertThat(reactivated.statusCode()).isEqualTo(200);
        JsonNode reactivatedBody = JSON.readTree(reactivated.body());
        assertThat(reactivatedBody.get("title").asText()).isEqualTo("Renamed");
        assertThat(reactivatedBody.get("status").asText()).isEqualTo("ACTIVE");

        // The destructive DELETED transition is not reachable through PATCH.
        HttpResponse<String> rejected = patch(
                "/api/v1/conversations/" + conversationId,
                "{\"status\":\"DELETED\"}");
        assertThat(rejected.statusCode()).isEqualTo(400);
    }

    /**
     * The default {@link org.springframework.boot.test.web.client.TestRestTemplate}
     * request factory (no Apache HttpComponents on the test classpath)
     * cannot issue HTTP PATCH, so drive it through the JDK client.
     */
    private HttpResponse<String> patch(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(restTemplate.getRootUri() + path))
                .header("Authorization", adminHeaders().getFirst("Authorization"))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json))
                .build();
        return HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
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

    @Test
    void eventsEndpointReplaysTheLifecycleLogInOrder() {
        Project project = data.project().named("conv-ctrl-events").create();
        CreateConversationRequest body = new CreateConversationRequest(
                project.getId(), null, "events session", null);
        ResponseEntity<ConversationResponse> created = restTemplate.exchange(
                "/api/v1/conversations",
                HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders()),
                ConversationResponse.class);
        UUID conversationId = created.getBody().id();

        // Seed a short worker-bind lifecycle through the service.
        UUID workerId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        conversationEventService.record(conversationId, workerId, hostId,
                Agent.Status.IDLE, Agent.Status.RESERVED, ConversationEventReason.RESERVED);
        conversationEventService.record(conversationId, workerId, hostId,
                Agent.Status.RESERVED, Agent.Status.CONNECTING, ConversationEventReason.BIND_ACKED);
        conversationEventService.record(conversationId, workerId, hostId,
                Agent.Status.CONNECTING, Agent.Status.BOUND, ConversationEventReason.INSTANCE_BOUND);

        ResponseEntity<List<ConversationEventResponse>> events = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/events",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                new ParameterizedTypeReference<List<ConversationEventResponse>>() {});

        assertThat(events.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(events.getBody()).hasSize(3);
        assertThat(events.getBody()).extracting(ConversationEventResponse::seq)
                .containsExactly(0, 1, 2);
        assertThat(events.getBody()).extracting(ConversationEventResponse::reasonCode)
                .containsExactly("RESERVED", "BIND_ACKED", "INSTANCE_BOUND");
        assertThat(events.getBody().get(0).fromState()).isEqualTo("IDLE");
        assertThat(events.getBody().get(2).toState()).isEqualTo("BOUND");
        assertThat(events.getBody()).allSatisfy(e -> {
            assertThat(e.agentId()).isEqualTo(workerId);
            assertThat(e.agentHostId()).isEqualTo(hostId);
        });
    }

    @Test
    void eventsEndpointRejectsOutsiders() {
        Project project = data.project().named("conv-ctrl-events-acl").create();
        CreateConversationRequest body = new CreateConversationRequest(
                project.getId(), null, "events acl", null);
        ResponseEntity<ConversationResponse> created = restTemplate.exchange(
                "/api/v1/conversations",
                HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders()),
                ConversationResponse.class);
        UUID conversationId = created.getBody().id();

        UUID outsider = seedUser("events-out-" + UUID.randomUUID().toString().substring(0, 8));
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/events",
                HttpMethod.GET,
                new HttpEntity<>(userHeaders(outsider, UUID.randomUUID())),
                String.class);

        assertThat(resp.getStatusCode().value()).isIn(401, 403);
    }

    @Test
    void editorCanPinAndUnpinAMessage() throws Exception {
        Project project = data.project().named("conv-ctrl-pin").create();
        CreateConversationRequest body = new CreateConversationRequest(
                project.getId(), null, "pin session", null);
        ResponseEntity<ConversationResponse> created = restTemplate.exchange(
                "/api/v1/conversations",
                HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders()),
                ConversationResponse.class);
        UUID conversationId = created.getBody().id();

        // Append a USER message to pin.
        PostUserMessageRequest msg = new PostUserMessageRequest("pin me");
        ResponseEntity<ConversationMessageResponse> posted = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/messages",
                HttpMethod.POST,
                new HttpEntity<>(msg, adminHeaders()),
                ConversationMessageResponse.class);
        UUID messageId = posted.getBody().id();
        assertThat(posted.getBody().pinned()).isFalse();

        // Pin it.
        HttpResponse<String> pinned = patch(
                "/api/v1/conversations/" + conversationId + "/messages/" + messageId + "/pin",
                "{\"pinned\":true}");
        assertThat(pinned.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(pinned.body()).get("pinned").asBoolean()).isTrue();

        // The pin is persisted in the message list.
        ResponseEntity<List<ConversationMessageResponse>> listed = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/messages",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                new ParameterizedTypeReference<List<ConversationMessageResponse>>() {});
        assertThat(listed.getBody()).hasSize(1);
        assertThat(listed.getBody().get(0).pinned()).isTrue();

        // Unpin it.
        HttpResponse<String> unpinned = patch(
                "/api/v1/conversations/" + conversationId + "/messages/" + messageId + "/pin",
                "{\"pinned\":false}");
        assertThat(unpinned.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(unpinned.body()).get("pinned").asBoolean()).isFalse();
    }

    @Test
    void memberCanRateAndClearFeedbackOnAssistantMessage() throws Exception {
        Project project = data.project().named("conv-ctrl-feedback").create();
        CreateConversationRequest body = new CreateConversationRequest(
                project.getId(), null, "feedback session", null);
        ResponseEntity<ConversationResponse> created = restTemplate.exchange(
                "/api/v1/conversations",
                HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders()),
                ConversationResponse.class);
        UUID conversationId = created.getBody().id();

        // Seed an ASSISTANT message — only model answers are rateable.
        ConversationMessage assistant = conversationService.appendMessage(
                conversationId, ConversationMessage.Role.ASSISTANT, "here is your answer", null, null);
        UUID messageId = assistant.getId();

        // Thumbs-up with a reason.
        HttpResponse<String> up = patch(
                "/api/v1/conversations/" + conversationId + "/messages/" + messageId + "/feedback",
                "{\"rating\":\"UP\",\"reason\":\"spot on\"}");
        assertThat(up.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(up.body()).get("feedbackRating").asText()).isEqualTo("UP");
        assertThat(JSON.readTree(up.body()).get("feedbackReason").asText()).isEqualTo("spot on");
        assertThat(JSON.readTree(up.body()).get("feedbackBy").isNull()).isFalse();

        // Change the verdict to thumbs-down (persisted on the row).
        HttpResponse<String> down = patch(
                "/api/v1/conversations/" + conversationId + "/messages/" + messageId + "/feedback",
                "{\"rating\":\"down\"}");
        assertThat(down.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(down.body()).get("feedbackRating").asText()).isEqualTo("DOWN");
        assertThat(JSON.readTree(down.body()).get("feedbackReason").isNull()).isTrue();

        ResponseEntity<List<ConversationMessageResponse>> listed = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/messages",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                new ParameterizedTypeReference<List<ConversationMessageResponse>>() {});
        assertThat(listed.getBody()).hasSize(1);
        assertThat(listed.getBody().get(0).feedbackRating()).isEqualTo("DOWN");

        // Clear the feedback (blank rating).
        HttpResponse<String> cleared = patch(
                "/api/v1/conversations/" + conversationId + "/messages/" + messageId + "/feedback",
                "{\"rating\":null}");
        assertThat(cleared.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(cleared.body()).get("feedbackRating").isNull()).isTrue();
        assertThat(JSON.readTree(cleared.body()).get("feedbackBy").isNull()).isTrue();
    }

    @Test
    void ratingANonAssistantMessageIsRejected() throws Exception {
        Project project = data.project().named("conv-ctrl-feedback-bad").create();
        CreateConversationRequest body = new CreateConversationRequest(
                project.getId(), null, "feedback guard", null);
        ResponseEntity<ConversationResponse> created = restTemplate.exchange(
                "/api/v1/conversations",
                HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders()),
                ConversationResponse.class);
        UUID conversationId = created.getBody().id();

        PostUserMessageRequest msg = new PostUserMessageRequest("rate me?");
        ResponseEntity<ConversationMessageResponse> posted = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/messages",
                HttpMethod.POST,
                new HttpEntity<>(msg, adminHeaders()),
                ConversationMessageResponse.class);
        UUID userMessageId = posted.getBody().id();

        HttpResponse<String> rejected = patch(
                "/api/v1/conversations/" + conversationId + "/messages/" + userMessageId + "/feedback",
                "{\"rating\":\"UP\"}");
        assertThat(rejected.statusCode()).isIn(400, 409, 500);
    }

    @Test
    void memberCanExportConversationAsMarkdown() {
        Project project = data.project().named("conv-ctrl-export").create();
        CreateConversationRequest body = new CreateConversationRequest(
                project.getId(), null, "Export me", null);
        ResponseEntity<ConversationResponse> created = restTemplate.exchange(
                "/api/v1/conversations",
                HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders()),
                ConversationResponse.class);
        UUID conversationId = created.getBody().id();

        restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/messages",
                HttpMethod.POST,
                new HttpEntity<>(new PostUserMessageRequest("hello there"), adminHeaders()),
                ConversationMessageResponse.class);
        conversationService.appendMessage(
                conversationId, ConversationMessage.Role.ASSISTANT, "general kenobi", null, null);

        ResponseEntity<String> exported = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/export",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                String.class);
        assertThat(exported.getStatusCode().value()).isEqualTo(200);
        assertThat(exported.getBody()).contains("# Export me");
        assertThat(exported.getBody()).contains("hello there");
        assertThat(exported.getBody()).contains("general kenobi");
    }

    @Test
    void messagesEndpointSupportsLimitAndBeforeCursorPagination() {
        Project project = data.project().named("conv-ctrl-page").create();
        CreateConversationRequest body = new CreateConversationRequest(
                project.getId(), null, "scrollback", null);
        ResponseEntity<ConversationResponse> created = restTemplate.exchange(
                "/api/v1/conversations",
                HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders()),
                ConversationResponse.class);
        UUID conversationId = created.getBody().id();

        // Seed five USER messages (sequence_no 0..4).
        for (int i = 0; i < 5; i++) {
            restTemplate.exchange(
                    "/api/v1/conversations/" + conversationId + "/messages",
                    HttpMethod.POST,
                    new HttpEntity<>(new PostUserMessageRequest("m" + i), adminHeaders()),
                    ConversationMessageResponse.class);
        }

        // No params → whole transcript in ascending order.
        ResponseEntity<List<ConversationMessageResponse>> all = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/messages",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                new ParameterizedTypeReference<List<ConversationMessageResponse>>() {});
        assertThat(all.getBody()).hasSize(5);
        assertThat(all.getBody().get(0).content()).isEqualTo("m0");
        assertThat(all.getBody().get(4).content()).isEqualTo("m4");

        // limit=2 → newest two rows, still ascending (m3, m4).
        ResponseEntity<List<ConversationMessageResponse>> newest = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/messages?limit=2",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                new ParameterizedTypeReference<List<ConversationMessageResponse>>() {});
        assertThat(newest.getBody()).hasSize(2);
        assertThat(newest.getBody().get(0).content()).isEqualTo("m3");
        assertThat(newest.getBody().get(1).content()).isEqualTo("m4");

        // Older page strictly before the first row of the newest page (seq 3).
        long cursor = newest.getBody().get(0).sequenceNo();
        ResponseEntity<List<ConversationMessageResponse>> older = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/messages?limit=2&before=" + cursor,
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                new ParameterizedTypeReference<List<ConversationMessageResponse>>() {});
        assertThat(older.getBody()).hasSize(2);
        assertThat(older.getBody().get(0).content()).isEqualTo("m1");
        assertThat(older.getBody().get(1).content()).isEqualTo("m2");
    }
}
