// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.user.User;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.agent.dto.AgentProfileResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Public agent-profile roster ({@code GET /api/v1/agent-profiles}).
 *
 * <p>Authoring surfaces (workflow editor) load this list as project
 * members — the {@code SecurityConfig} admin ACL must not gate it, the
 * projection must carry identity + version pointers but never the
 * behaviour contract (system prompt, tool codes) or draft state, and
 * anonymous callers must get 401.</p>
 */
@DisplayName("Agent Profile public roster")
class AgentProfileControllerTest extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private AgentProfileService profileService;

    @Test
    @DisplayName("project-scoped user lists active profiles via the public endpoint")
    void projectUserCanListPublicProfiles() {
        // A profile whose published version carries the behaviour contract.
        AgentProfile seeded = profileService.createProfile(
                "pub-roster-" + UUID.randomUUID().toString().substring(0, 8),
                "Public roster fixture",
                List.of(),
                List.of(),
                Set.of(),
                "Secret system prompt",
                null);
        UUID profileId = seeded.getId();

        // A project-scoped EDITOR (the workflow-authoring persona) — a
        // real user row, or the JWT filter drops the token to anonymous.
        var project = data.project()
                .named("pub-roster-prj-" + UUID.randomUUID().toString().substring(0, 8))
                .create();
        HttpHeaders editorHeaders = userHeaders(createUserRow(), project.getId());

        ResponseEntity<List<AgentProfileResponse>> listed = restTemplate.exchange(
                "/api/v1/agent-profiles",
                HttpMethod.GET,
                new HttpEntity<>(editorHeaders),
                new ParameterizedTypeReference<List<AgentProfileResponse>>() {});
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody()).isNotNull();

        var ours = listed.getBody().stream()
                .filter(p -> profileId.equals(p.getId()))
                .findFirst().orElseThrow();
        // Identity + published-version pointer: present.
        assertThat(ours.getName()).startsWith("pub-roster-");
        assertThat(ours.getPublishedVersionId()).isNotNull();
        assertThat(ours.getPublishedVersionNumber()).isEqualTo(1);
        assertThat(ours.getStatus()).isEqualTo("ACTIVE");
        // Behaviour contract + draft state: absent from the public projection.
        assertThat(ours.getSystemPrompt()).isNull();
        assertThat(ours.getToolCodes()).isNullOrEmpty();
        assertThat(ours.getDraftVersionId()).isNull();
    }

    @Test
    @DisplayName("inactive profiles are filtered out by default, included with activeOnly=false")
    void inactiveFilteredByDefault() {
        UUID activeId = profileService.createProfile(
                "pub-active-" + UUID.randomUUID().toString().substring(0, 8),
                null, List.of(), List.of(), Set.of(), null, null).getId();
        UUID inactiveId = profileService.createProfile(
                "pub-inactive-" + UUID.randomUUID().toString().substring(0, 8),
                null, List.of(), List.of(), Set.of(), null, null).getId();
        profileService.deactivateProfile(inactiveId);

        HttpHeaders adminHdr = adminHeaders();
        ResponseEntity<List<AgentProfileResponse>> activeList = restTemplate.exchange(
                "/api/v1/agent-profiles?activeOnly=true",
                HttpMethod.GET,
                new HttpEntity<>(adminHdr),
                new ParameterizedTypeReference<List<AgentProfileResponse>>() {});
        assertThat(activeList.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(activeList.getBody()).extracting(AgentProfileResponse::getId)
                .contains(activeId)
                .doesNotContain(inactiveId);

        ResponseEntity<List<AgentProfileResponse>> allList = restTemplate.exchange(
                "/api/v1/agent-profiles?activeOnly=false",
                HttpMethod.GET,
                new HttpEntity<>(adminHdr),
                new ParameterizedTypeReference<List<AgentProfileResponse>>() {});
        assertThat(allList.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(allList.getBody()).extracting(AgentProfileResponse::getId)
                .contains(activeId, inactiveId);
    }

    @Test
    @DisplayName("anonymous callers are rejected")
    void anonymousRejected() {
        // The engine's filter chain has no 401 entry point for /api/v1/**
        // — an unauthenticated principal lands on the 403 deny rule.
        ResponseEntity<String> anon = restTemplate.exchange(
                "/api/v1/agent-profiles",
                HttpMethod.GET,
                HttpEntity.EMPTY,
                String.class);
        assertThat(anon.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** Insert a user row so the JWT filter authenticates the token. */
    private UUID createUserRow() {
        User user = new User();
        user.setEmail("pub-roster-" + UUID.randomUUID() + "@test.local");
        user.setName("Public Roster Editor");
        user.setPasswordHash("$2a$10$dummy");
        user.setProviderCode(ai.myrmec.engine.user.AuthenticationProvider.LOCAL_CODE);
        user.setIsActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user).getId();
    }
}