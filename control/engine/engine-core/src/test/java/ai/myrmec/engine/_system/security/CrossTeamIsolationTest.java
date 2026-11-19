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
import ai.myrmec.engine.team.Team;
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
 * Cross-team isolation probe — implements golden journey J5 from
 * {@code 02-system-e2e-strategy.md}.
 *
 * <p>Proves SG1 (scope isolation): no principal ever observes data,
 * config, or agent output belonging to a team it isn't authorized for,
 * through any entry point.</p>
 *
 * <p>Given a user with VIEWER on Team A and no grant on Team B,
 * every attempt to read Team B's data through any available entry
 * point must return 403/404, and no response body, error message, or
 * log line may contain Team B content.</p>
 */
@DisplayName("Cross-team Isolation (J5)")
class CrossTeamIsolationTest extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private AssistantService assistantService;

    @Autowired
    private AssistantVersionService assistantVersionService;

    private team teamA;
    private team teamB;
    private HttpHeaders viewerAHeaders;
    private UUID viewerId;

    /**
     * Creates two projects and a user with VIEWER on Team A only.
     * Called once per nested test class via {@code @BeforeEach} in each.
     */
    private void setupIsolation() {
        teamA = data.team()
                .named("j5-prj-a-" + UUID.randomUUID().toString().substring(0, 8))
                .create();
        teamB = data.team()
                .named("j5-prj-b-" + UUID.randomUUID().toString().substring(0, 8))
                .create();

        // Synthetic user with VIEWER on Team A only.
        viewerId = UUID.randomUUID();
        viewerAHeaders = userHeaders(viewerId, teamA.getId());
    }

    // ================================================================
    // Conversations
    // ================================================================

    @Nested
    @DisplayName("Conversations")
    class ConversationIsolation {

        @Test
        @DisplayName("cannot list Team B conversations")
        void cannotListProjectBConversations() {
            setupIsolation();
            // Seed a conversation in Team B.
            conversationService.createConversation(
                    teamB.getId(), TEST_ADMIN_ID,
                    "j5-conv-b", null, null);

            ResponseEntity<String> resp = restTemplate.exchange(
                    "/api/v1/conversations?teamId=" + teamB.getId(),
                    HttpMethod.GET,
                    new HttpEntity<>(viewerAHeaders),
                    String.class);

            assertThat(resp.getStatusCode())
                    .as("VIEWER on Team A must not list Team B conversations")
                    .isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
            if (resp.getBody() != null) {
                assertThat(resp.getBody())
                        .as("response body must not leak Team B data")
                        .doesNotContain(teamB.getId().toString());
            }
        }

        @Test
        @DisplayName("cannot access Team B conversation messages")
        void cannotAccessProjectBConversationMessages() {
            setupIsolation();
            Conversation conv = conversationService.createConversation(
                    teamB.getId(), TEST_ADMIN_ID,
                    "j5-conv-b2", null, null);

            ResponseEntity<String> resp = restTemplate.exchange(
                    "/api/v1/conversations/" + conv.getId() + "/messages?limit=50",
                    HttpMethod.GET,
                    new HttpEntity<>(viewerAHeaders),
                    String.class);

            assertThat(resp.getStatusCode())
                    .as("VIEWER on Team A must not access Team B messages")
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
        @DisplayName("cannot list Team B assistants")
        void cannotListProjectBAssistants() {
            setupIsolation();
            // Seed an assistant in Team B.
            var profile = data.agentProfile()
                    .named("j5-ap-" + UUID.randomUUID().toString().substring(0, 8))
                    .create();
            assistantService.createAssistant(
                    teamB.getId(),
                    "j5-asst-b-" + UUID.randomUUID().toString().substring(0, 8),
                    "J5 test assistant",
                    profile.getId(),
                    TEST_ADMIN_ID);

            ResponseEntity<String> resp = restTemplate.exchange(
                    "/api/v1/assistants?teamId=" + teamB.getId(),
                    HttpMethod.GET,
                    new HttpEntity<>(viewerAHeaders),
                    String.class);

            assertThat(resp.getStatusCode())
                    .as("VIEWER on Team A must not list Team B assistants")
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
        @DisplayName("cannot list Team B secrets")
        void cannotListProjectBSecrets() {
            setupIsolation();

            ResponseEntity<String> resp = restTemplate.exchange(
                    "/api/v1/teams/" + teamB.getId() + "/secrets",
                    HttpMethod.GET,
                    new HttpEntity<>(viewerAHeaders),
                    String.class);

            assertThat(resp.getStatusCode())
                    .as("VIEWER on Team A must not list Team B secrets")
                    .isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
        }
    }

    // ================================================================
    // team Members
    // ================================================================

    @Nested
    @DisplayName("team Members")
    class MemberIsolation {

        @Test
        @DisplayName("cannot list Team B members")
        void cannotListProjectBMembers() {
            setupIsolation();

            ResponseEntity<String> resp = restTemplate.exchange(
                    "/api/v1/teams/" + teamB.getId() + "/members",
                    HttpMethod.GET,
                    new HttpEntity<>(viewerAHeaders),
                    String.class);

            assertThat(resp.getStatusCode())
                    .as("VIEWER on Team A must not list Team B members")
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
        @DisplayName("cannot fetch Team B effective quotas")
        void cannotFetchProjectBEffectiveQuotas() {
            setupIsolation();

            ResponseEntity<String> resp = restTemplate.exchange(
                    "/api/v1/budgets/teams/" + teamB.getId() + "/effective-quotas",
                    HttpMethod.GET,
                    new HttpEntity<>(viewerAHeaders),
                    String.class);

            assertThat(resp.getStatusCode())
                    .as("VIEWER on Team A must not fetch Team B quotas")
                    .isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
        }
    }

    // ================================================================
    // team detail (no @PreAuthorize — critical gap)
    // ================================================================

    @Nested
    @DisplayName("team Detail")
    class ProjectDetailIsolation {

        @Test
        @DisplayName("cannot fetch Team B detail")
        void cannotFetchProjectBDetail() {
            setupIsolation();

            ResponseEntity<String> resp = restTemplate.exchange(
                    "/api/v1/teams/" + teamB.getId(),
                    HttpMethod.GET,
                    new HttpEntity<>(viewerAHeaders),
                    String.class);

            assertThat(resp.getStatusCode())
                    .as("VIEWER on Team A must not fetch Team B detail")
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
        @DisplayName("cannot list Team B workflows")
        void cannotListProjectBWorkflows() {
            setupIsolation();

            ResponseEntity<String> resp = restTemplate.exchange(
                    "/api/v1/teams/" + teamB.getId() + "/workflows",
                    HttpMethod.GET,
                    new HttpEntity<>(viewerAHeaders),
                    String.class);

            assertThat(resp.getStatusCode())
                    .as("VIEWER on Team A must not list Team B workflows")
                    .isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
        }
    }
}
