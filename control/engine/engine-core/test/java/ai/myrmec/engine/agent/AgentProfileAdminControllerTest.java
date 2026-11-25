// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.dto.AgentProfileCreateRequest;
import ai.myrmec.engine.agent.dto.AgentProfileDraftUpdateRequest;
import ai.myrmec.engine.agent.dto.AgentProfileResponse;
import ai.myrmec.engine.agent.dto.AgentProfileUpdateRequest;
import ai.myrmec.engine.agent.dto.AgentProfileVersionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Admin agent-profile endpoints and the versioned create/update flow
 * (design 2026-09-22, sections 2.1 and 2.2):
 *
 * <ul>
 *   <li>identity-only create returns draftVersionId set and
 *       publishedVersionId null</li>
 *   <li>content-bearing create keeps publishing v1</li>
 *   <li>the identity-only PUT never touches the versions</li>
 * </ul>
 */
@DisplayName("Agent Profile admin flow (versioned create/update)")
class AgentProfileAdminControllerTest extends IntegrationTestBase {

    @Autowired
    private AgentProfileVersionService versionService;

    @Autowired
    private AgentProfileService profileService;

    @Test
    @DisplayName("identity-only create returns draftVersionId set, publishedVersionId null")
    void identityOnlyCreateReturnsDraftPointer() {
        AgentProfileCreateRequest req = new AgentProfileCreateRequest();
        req.setName("adm-draft-create-" + UUID.randomUUID().toString().substring(0, 8));
        req.setDescription("Identity-only create");

        ResponseEntity<AgentProfileResponse> created = restTemplate.exchange(
                "/api/v1/admin/agent-profiles",
                HttpMethod.POST,
                new HttpEntity<>(req, adminHeaders()),
                AgentProfileResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        UUID profileId = created.getBody().getId();

        // The create response itself already carries the draft pointer and
        // no published version.
        assertThat(created.getBody().getDraftVersionId()).isNotNull();
        assertThat(created.getBody().getDraftVersionNumber()).isEqualTo(1);
        assertThat(created.getBody().getPublishedVersionId()).isNull();
        assertThat(created.getBody().getPublishedVersionNumber()).isNull();

        // A subsequent get reports the same shape.
        ResponseEntity<AgentProfileResponse> fetched = restTemplate.exchange(
                "/api/v1/admin/agent-profiles/" + profileId,
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                AgentProfileResponse.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isNotNull();
        assertThat(fetched.getBody().getDraftVersionId())
                .isEqualTo(created.getBody().getDraftVersionId());
        assertThat(fetched.getBody().getPublishedVersionId()).isNull();

        // The version list carries exactly one DRAFT v1 row.
        ResponseEntity<List<AgentProfileVersionResponse>> versions = restTemplate.exchange(
                "/api/v1/admin/agent-profiles/" + profileId + "/versions",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                new ParameterizedTypeReference<List<AgentProfileVersionResponse>>() {});
        assertThat(versions.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(versions.getBody()).hasSize(1);
        assertThat(versions.getBody().get(0).status()).isEqualTo("DRAFT");
        assertThat(versions.getBody().get(0).versionNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("content-bearing create publishes v1 (backward compatible)")
    void contentBearingCreatePublishesV1() {
        AgentProfileCreateRequest req = new AgentProfileCreateRequest();
        req.setName("adm-content-create-" + UUID.randomUUID().toString().substring(0, 8));
        req.setDescription("Content create");
        req.setSystemPrompt("Published at creation time.");

        ResponseEntity<AgentProfileResponse> created = restTemplate.exchange(
                "/api/v1/admin/agent-profiles",
                HttpMethod.POST,
                new HttpEntity<>(req, adminHeaders()),
                AgentProfileResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().getPublishedVersionNumber()).isEqualTo(1);
        assertThat(created.getBody().getPublishedVersionId()).isNotNull();
        assertThat(created.getBody().getDraftVersionId()).isNull();
    }

    @Test
    @DisplayName("draft v1 from an identity-only create can be edited and published to v1")
    void identityOnlyCreateThenEditAndPublishDraft() {
        AgentProfileCreateRequest req = new AgentProfileCreateRequest();
        req.setName("adm-draft-flow-" + UUID.randomUUID().toString().substring(0, 8));
        req.setDescription("Draft flow");

        UUID profileId = restTemplate.exchange(
                "/api/v1/admin/agent-profiles",
                HttpMethod.POST,
                new HttpEntity<>(req, adminHeaders()),
                AgentProfileResponse.class).getBody().getId();

        // PATCH the draft (the detail page's draft editor).
        AgentProfileDraftUpdateRequest patch = new AgentProfileDraftUpdateRequest();
        patch.setSystemPrompt("Prompt authored after create.");
        patch.setToolCodes(Set.of());
        ResponseEntity<AgentProfileVersionResponse> patched = restTemplate.exchange(
                "/api/v1/admin/agent-profiles/" + profileId + "/draft",
                HttpMethod.PATCH,
                new HttpEntity<>(patch, adminHeaders()),
                AgentProfileVersionResponse.class);
        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patched.getBody()).isNotNull();
        assertThat(patched.getBody().systemPrompt()).isEqualTo("Prompt authored after create.");

        // Publish the draft: version 1 becomes the live published version.
        ResponseEntity<AgentProfileVersionResponse> published = restTemplate.exchange(
                "/api/v1/admin/agent-profiles/" + profileId + "/publish",
                HttpMethod.POST,
                new HttpEntity<>(adminHeaders()),
                AgentProfileVersionResponse.class);
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(published.getBody()).isNotNull();
        assertThat(published.getBody().versionNumber()).isEqualTo(1);
        assertThat(published.getBody().status()).isEqualTo("PUBLISHED");

        // The profile now reports published v1 and no draft.
        ResponseEntity<AgentProfileResponse> fetched = restTemplate.exchange(
                "/api/v1/admin/agent-profiles/" + profileId,
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                AgentProfileResponse.class);
        assertThat(fetched.getBody()).isNotNull();
        assertThat(fetched.getBody().getPublishedVersionNumber()).isEqualTo(1);
        assertThat(fetched.getBody().getDraftVersionId()).isNull();
    }

    @Test
    @DisplayName("PUT with only name/description leaves versions untouched")
    void identityOnlyPutDoesNotTouchVersions() {
        // Content-bearing create -> published v1; then open a draft.
        AgentProfileCreateRequest req = new AgentProfileCreateRequest();
        req.setName("adm-rename-" + UUID.randomUUID().toString().substring(0, 8));
        req.setSystemPrompt("Published before the rename.");
        UUID profileId = restTemplate.exchange(
                "/api/v1/admin/agent-profiles",
                HttpMethod.POST,
                new HttpEntity<>(req, adminHeaders()),
                AgentProfileResponse.class).getBody().getId();
        UUID publishedVersionId = restTemplate.exchange(
                "/api/v1/admin/agent-profiles/" + profileId,
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                AgentProfileResponse.class).getBody().getPublishedVersionId();

        // Rename through the identity-only PUT: every behaviour field null.
        AgentProfileUpdateRequest rename = new AgentProfileUpdateRequest();
        rename.setName("adm-rename-after-" + UUID.randomUUID().toString().substring(0, 8));
        rename.setDescription("Identity-only PUT");
        ResponseEntity<AgentProfileResponse> updated = restTemplate.exchange(
                "/api/v1/admin/agent-profiles/" + profileId,
                HttpMethod.PUT,
                new HttpEntity<>(rename, adminHeaders()),
                AgentProfileResponse.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody()).isNotNull();
        assertThat(updated.getBody().getName()).isEqualTo(rename.getName());
        // The published version pointer is unchanged and no draft appeared.
        assertThat(updated.getBody().getPublishedVersionId()).isEqualTo(publishedVersionId);
        assertThat(updated.getBody().getDraftVersionId()).isNull();

        // No new version row: the list still contains exactly one version.
        ResponseEntity<List<AgentProfileVersionResponse>> versions = restTemplate.exchange(
                "/api/v1/admin/agent-profiles/" + profileId + "/versions",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                new ParameterizedTypeReference<List<AgentProfileVersionResponse>>() {});
        assertThat(versions.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(versions.getBody()).hasSize(1);
        assertThat(versions.getBody().get(0).status()).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("PUT with behaviour content still runs the draft-publish cycle")
    void contentBearingPutRepublishes() {
        AgentProfileCreateRequest req = new AgentProfileCreateRequest();
        req.setName("adm-content-put-" + UUID.randomUUID().toString().substring(0, 8));
        req.setSystemPrompt("v1 prompt");
        UUID profileId = restTemplate.exchange(
                "/api/v1/admin/agent-profiles",
                HttpMethod.POST,
                new HttpEntity<>(req, adminHeaders()),
                AgentProfileResponse.class).getBody().getId();

        AgentProfileUpdateRequest update = new AgentProfileUpdateRequest();
        update.setName("adm-content-put-" + UUID.randomUUID().toString().substring(0, 8));
        update.setSystemPrompt("v2 prompt");
        ResponseEntity<AgentProfileResponse> updated = restTemplate.exchange(
                "/api/v1/admin/agent-profiles/" + profileId,
                HttpMethod.PUT,
                new HttpEntity<>(update, adminHeaders()),
                AgentProfileResponse.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody()).isNotNull();
        // v2 published, the draft consumed by the cycle is closed.
        assertThat(updated.getBody().getPublishedVersionNumber()).isEqualTo(2);
        assertThat(updated.getBody().getDraftVersionId()).isNull();
    }
}