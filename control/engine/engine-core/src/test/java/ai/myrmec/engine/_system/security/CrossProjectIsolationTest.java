// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * ⚠️ OSS SECURITY-INVARIANT CARVE-OUT (decisions 2026-08-17)
 * This test is part of the standing OSS security safety net. It must never be
 * deleted or weakened. If it fails, it indicates a security regression.
 */
package ai.myrmec.engine._system.security;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.assistant.AssistantService;
import ai.myrmec.engine.assistant.AssistantVersionService;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationService;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-project isolation probe — implements golden journey J5 from
 * {@code 02-system-e2e-strategy.md}.
 *
 * <p>Proves SG1 (scope isolation): no principal ever observes data,
 * config, or agent output belonging to a project it isn't authorized for,
 * through any entry point.</p>
 *
 * <p>Given a user with VIEWER on Project A and no grant on Project B,
 * every attempt to read Project B's data through any available entry
 * point must return 403/404, and no response body, error message, or
 * log line may contain Project B content.</p>
 */
@DisplayName("Cross-Project Isolation (J5)")
class CrossProjectIsolationTest extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private AssistantService assistantService;

    @Autowired
    private AssistantVersionService assistantVersionService;

    private Project projectA;
    private Project projectB;
    private HttpHeaders viewerAHeaders;
    private UUID viewerId;

    /**
     * Creates two projects and a user with VIEWER on Project A only.
     * Called once per nested test class via {@code @BeforeEach} in each.
     */
    private void setupIsolation() {
        projectA = data.project()
                .named("j5-prj-a-" + UUID.randomUUID().toString().substring(0, 8))
                .create();
        projectB = data.project()
                .named("j5-prj-b-" + UUID.randomUUID().toString().substring(0, 8))
                .create();

        // Synthetic user with VIEWER on Project A only.
        viewerId = UUID.randomUUID();
        viewerAHeaders = userHeaders(viewerId, projectA.getId());
    }

    // ================================================================
    // Conversations
    // ================================================================

    @Nested
    @DisplayName("Conversations")
    class ConversationIsolation {

        @Test
        @DisplayName("cannot list Project B conversations")
        void cannotListProjectBConversations() {
            setupIsolation();
            // Seed a conversation in Project B.
            conversationService.createConversation(
                    projectB.getId(), TEST_ADMIN_ID,
                    "j5-conv-b", null, null);

            ResponseEntity<String> resp = restTemplate.exchange(
                    "/api/v1/conversations?projectId=" + projectB.getId(),
                    HttpMethod.GET,
                    new HttpEntity<>(viewerAHeaders),
                    String.class);

            assertThat(resp.getStatusCode())
                    .as("VIEWER on Project A must not list Project B conversations")
                    .isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
            if (resp.getBody() != null) {
                assertThat(resp.getBody())
                        .as("response body must not leak Project B data")
                        .doesNotContain(projectB.getId().toString());
            }
        }

        @Test
        @DisplayName("cannot access Project B conversation messages")
        void cannotAccessProjectBConversationMessages() {
            setupIsolation();
            Conversation conv = conversationService.createConversation(
                    projectB.getId(), TEST_ADMIN_ID,
                    "j5-conv-b2", null, null);

            ResponseEntity<String> resp = restTemplate.exchange(
                    "/api/v1/conversations/" + conv.getId() + "/messages?limit=50",
                    HttpMethod.GET,
                    new HttpEntity<>(viewerAHeaders),
                    String.class);

            assertThat(resp.getStatusCode())
                    .as("VIEWER on Project A must not access Project B messages")
                    .isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
        }
    }

    // ================================================================
    // Assistants
    // ================================================================

    @Nested
    @DisplayName("Assistants")
    class AssistantIsolation {

        @Test
        @DisplayName("cannot list Project B assistants")
        void cannotListProjectBAssistants() {
            setupIsolation();
            // Seed an assistant in Project B.
            var profile = data.agentProfile()
                    .named("j5-ap-" + UUID.randomUUID().toString().substring(0, 8))
                    .create();
            assistantService.createAssistant(
                    projectB.getId(),
                    "j5-asst-b-" + UUID.randomUUID().toString().substring(0, 8),
                    "J5 test assistant",
                    profile.getId(),
                    TEST_ADMIN_ID);

            ResponseEntity<String> resp = restTemplate.exchange(
                    "/api/v1/assistants?projectId=" + projectB.getId(),
                    HttpMethod.GET,
                    new HttpEntity<>(viewerAHeaders),
                    String.class);

            assertThat(resp.getStatusCode())
                    .as("VIEWER on Project A must not list Project B assistants")
                    .isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
        }
    }

    // ================================================================
    // Secrets
    // ================================================================

    @Nested
    @DisplayName("Secrets")
    class SecretIsolation {

        @Test
        @DisplayName("cannot list Project B secrets")
        void cannotListProjectBSecrets() {
            setupIsolation();

            ResponseEntity<String> resp = restTemplate.exchange(
                    "/api/v1/projects/" + projectB.getId() + "/secrets",
                    HttpMethod.GET,
                    new HttpEntity<>(viewerAHeaders),
                    String.class);

            assertThat(resp.getStatusCode())
                    .as("VIEWER on Project A must not list Project B secrets")
                    .isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
        }
    }

    // ================================================================
    // Project Members
    // ================================================================

    @Nested
    @DisplayName("Project Members")
    class MemberIsolation {

        @Test
        @DisplayName("cannot list Project B members")
        void cannotListProjectBMembers() {
            setupIsolation();

            ResponseEntity<String> resp = restTemplate.exchange(
                    "/api/v1/projects/" + projectB.getId() + "/members",
                    HttpMethod.GET,
                    new HttpEntity<>(viewerAHeaders),
                    String.class);

            assertThat(resp.getStatusCode())
                    .as("VIEWER on Project A must not list Project B members")
                    .isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
        }
    }

    // ================================================================
    // Budgets / Quotas
    // ================================================================

    @Nested
    @DisplayName("Budgets / Quotas")
    class BudgetIsolation {

        @Test
        @DisplayName("cannot fetch Project B effective quotas")
        void cannotFetchProjectBEffectiveQuotas() {
            setupIsolation();

            ResponseEntity<String> resp = restTemplate.exchange(
                    "/api/v1/budgets/projects/" + projectB.getId() + "/effective-quotas",
                    HttpMethod.GET,
                    new HttpEntity<>(viewerAHeaders),
                    String.class);

            assertThat(resp.getStatusCode())
                    .as("VIEWER on Project A must not fetch Project B quotas")
                    .isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
        }
    }

    // ================================================================
    // Project detail (no @PreAuthorize — critical gap)
    // ================================================================

    @Nested
    @DisplayName("Project Detail")
    class ProjectDetailIsolation {

        @Test
        @DisplayName("cannot fetch Project B detail")
        void cannotFetchProjectBDetail() {
            setupIsolation();

            ResponseEntity<String> resp = restTemplate.exchange(
                    "/api/v1/projects/" + projectB.getId(),
                    HttpMethod.GET,
                    new HttpEntity<>(viewerAHeaders),
                    String.class);

            assertThat(resp.getStatusCode())
                    .as("VIEWER on Project A must not fetch Project B detail")
                    .isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
        }
    }

    // ================================================================
    // Workflows (no @PreAuthorize — critical gap)
    // ================================================================

    @Nested
    @DisplayName("Workflows")
    class WorkflowIsolation {

        @Test
        @DisplayName("cannot list Project B workflows")
        void cannotListProjectBWorkflows() {
            setupIsolation();

            ResponseEntity<String> resp = restTemplate.exchange(
                    "/api/v1/projects/" + projectB.getId() + "/workflows",
                    HttpMethod.GET,
                    new HttpEntity<>(viewerAHeaders),
                    String.class);

            assertThat(resp.getStatusCode())
                    .as("VIEWER on Project A must not list Project B workflows")
                    .isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
        }
    }
}
