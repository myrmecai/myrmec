// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine.inference.SessionContextAssembler;
import ai.myrmec.engine.model.Model;
import ai.myrmec.engine.model.ModelRepository;
import ai.myrmec.engine.project.ProjectService;
import ai.myrmec.engine.websocket.message.payload.SessionOpenPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plan Feature 0 (design §16.1): Agent Profile versioning.
 *
 * <p>Verifies the Draft → Publish lifecycle of the immutable behaviour
 * contract:</p>
 * <ul>
 *   <li>createProfile publishes version 1 (seed rule)</li>
 *   <li>publish freezes content — editing a published version is rejected</li>
 *   <li>later publishes create new versions and archive the previous one</li>
 *   <li>createDraft is idempotent (one open draft per profile)</li>
 *   <li>no-op publish (identical content) is rejected</li>
 *   <li>requirePublished fails when no published version exists</li>
 *   <li>the reserve-time pin resolves to the published version's content</li>
 *   <li>session.open carries the version's system prompt / default model</li>
 * </ul>
 */
@DisplayName("Feature 0: Agent Profile versioning (§16.1)")
class AgentProfileVersionServiceTest extends IntegrationTestBase {

    @Autowired
    private AgentProfileService agentProfileService;

    @Autowired
    private AgentProfileVersionService versionService;

    @Autowired
    private SessionContextAssembler sessionContextAssembler;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ModelRepository modelRepository;

    // ── helpers ──────────────────────────────────────────────

    /** A model row so the version's defaultModel FK resolves. */
    private String ensureModel(String code) {
        return modelRepository.findById(code).map(Model::getCode).orElseGet(() -> {
            Model m = new Model();
            m.setCode(code);
            m.setName("Test model " + code);
            m.setProvider("openai"); // seeded provider (004-models.xml)
            m.setModelId("test/" + code);
            modelRepository.save(m);
            return code;
        });
    }

    private AgentProfile newProfile(String systemPrompt, String defaultModel) {
        return agentProfileService.createProfile(
                "f0-profile-" + System.nanoTime(),
                "Feature 0 test profile",
                List.of("docker"),
                null,
                Set.of(),
                systemPrompt,
                defaultModel);
    }

    // ── seed rule: createProfile publishes v1 ─────────────────

    @Test
    @DisplayName("createProfile publishes version 1 in the PUBLISHED state")
    void createProfilePublishesVersion1() {
        AgentProfile profile = newProfile("v1 prompt", null);

        Optional<AgentProfileVersion> published =
                versionService.findPublished(profile.getId());
        assertThat(published).isPresent();
        assertThat(published.get().getVersionNumber()).isEqualTo(1);
        assertThat(published.get().getStatus()).isEqualTo(AgentProfileVersion.Status.PUBLISHED);
        assertThat(published.get().getSystemPrompt()).isEqualTo("v1 prompt");
    }

    // ── publish freezes content ───────────────────────────────

    @Test
    @DisplayName("published versions are immutable — updateDraft on PUBLISHED is rejected")
    void publishedVersionIsFrozen() {
        AgentProfile profile = newProfile("v1 prompt", null);
        AgentProfileVersion v1 = versionService.requirePublished(profile.getId());

        assertThatThrownBy(() -> versionService.updateDraft(
                v1.getId(), List.of(), null, "tampered prompt", null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("immutable");

        // content unchanged after the rejected edit
        assertThat(versionService.requirePublished(profile.getId()).getSystemPrompt())
                .isEqualTo("v1 prompt");
    }

    // ── later publishes create new versions ───────────────────

    @Test
    @DisplayName("a changed draft publishes as v2 and archives v1")
    void laterPublishCreatesNewVersionAndArchives() {
        AgentProfile profile = newProfile("v1 prompt", null);

        AgentProfileVersion draft = versionService.createDraft(profile.getId());
        assertThat(draft.getStatus()).isEqualTo(AgentProfileVersion.Status.DRAFT);
        assertThat(draft.getVersionNumber()).isEqualTo(2);

        versionService.updateDraft(draft.getId(), List.of("docker"), null, "v2 prompt", null, null);
        AgentProfileVersion v2 = versionService.publish(profile.getId(), null);

        assertThat(v2.getStatus()).isEqualTo(AgentProfileVersion.Status.PUBLISHED);
        assertThat(v2.getVersionNumber()).isEqualTo(2);
        assertThat(v2.getSystemPrompt()).isEqualTo("v2 prompt");

        // exactly one PUBLISHED version; v1 archived
        List<AgentProfileVersion> versions = versionService.listVersions(profile.getId());
        assertThat(versions).hasSize(2);
        assertThat(versions.stream()
                .filter(v -> v.getStatus() == AgentProfileVersion.Status.PUBLISHED))
                .hasSize(1);
        assertThat(versions.stream()
                .filter(v -> v.getStatus() == AgentProfileVersion.Status.ARCHIVED)
                .map(AgentProfileVersion::getVersionNumber))
                .containsExactly(1);
    }

    // ── createDraft idempotency ───────────────────────────────

    @Test
    @DisplayName("createDraft is idempotent — the open draft is returned unchanged")
    void createDraftIsIdempotent() {
        AgentProfile profile = newProfile("v1 prompt", null);

        AgentProfileVersion first = versionService.createDraft(profile.getId());
        AgentProfileVersion second = versionService.createDraft(profile.getId());

        assertThat(second.getId()).isEqualTo(first.getId());
    }

    // ── Draft/Publish lifecycle surface (§16.1 UI actions) ────

    @Test
    @DisplayName("getOpenDraft returns the open draft; findOpenDraft tolerates absence")
    void openDraftLookupSemantics() {
        AgentProfile profile = newProfile("v1 prompt", null);

        // No draft yet: the optional form is empty; the throwing form fails.
        assertThat(versionService.findOpenDraft(profile.getId())).isEmpty();
        assertThatThrownBy(() -> versionService.getOpenDraft(profile.getId()))
                .isInstanceOf(ai.myrmec.engine._system.exception.ResourceNotFoundException.class);

        AgentProfileVersion draft = versionService.createDraft(profile.getId());
        assertThat(versionService.findOpenDraft(profile.getId()))
                .map(AgentProfileVersion::getId)
                .contains(draft.getId());
        assertThat(versionService.getOpenDraft(profile.getId()).getId()).isEqualTo(draft.getId());
    }

    @Test
    @DisplayName("discardDraft frees the single-draft slot; the published version stays")
    void discardDraftFreesTheSlot() {
        AgentProfile profile = newProfile("v1 prompt", null);
        AgentProfileVersion publishedBefore = versionService.requirePublished(profile.getId());

        versionService.createDraft(profile.getId());
        versionService.discardDraft(profile.getId());

        // The slot is free: a NEW draft can be opened.
        assertThat(versionService.findOpenDraft(profile.getId())).isEmpty();
        AgentProfileVersion reopened = versionService.createDraft(profile.getId());
        assertThat(reopened.getStatus()).isEqualTo(AgentProfileVersion.Status.DRAFT);

        // The published version is untouched by the discard.
        AgentProfileVersion publishedAfter = versionService.requirePublished(profile.getId());
        assertThat(publishedAfter.getId()).isEqualTo(publishedBefore.getId());
        assertThat(publishedAfter.getSystemPrompt()).isEqualTo("v1 prompt");

        // Discarding with no open draft fails (404-mapped).
        versionService.discardDraft(profile.getId());
        assertThatThrownBy(() -> versionService.discardDraft(profile.getId()))
                .isInstanceOf(ai.myrmec.engine._system.exception.ResourceNotFoundException.class);
    }

    // ── no-op publish rejected ────────────────────────────────

    @Test
    @DisplayName("publishing identical content is rejected (no-op)")
    void noOpPublishIsRejected() {
        AgentProfile profile = newProfile("v1 prompt", null);

        // draft copies the published content; publishing it unchanged → reject
        versionService.createDraft(profile.getId());
        assertThatThrownBy(() -> versionService.publish(profile.getId(), null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("No changes to publish.");
    }

    // ── requirePublished fails when none ──────────────────────

    @Test
    @DisplayName("requirePublished fails when the profile has no published version")
    void requirePublishedFailsWithoutVersion() {
        // A raw Zone-1-only profile row (no version) — only reachable by
        // direct repository insert, since createProfile always publishes v1.
        AgentProfile profile = new AgentProfile();
        profile.setName("f0-bare-" + System.nanoTime());
        profile.setStatus(AgentProfile.Status.ACTIVE);

        assertThatThrownBy(() -> versionService.requirePublished(profile.getId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("no published version");
    }

    // ── reserve-time pin resolves published content ───────────

    @Test
    @DisplayName("the published-version resolution pins the version's content (reserve-time pin)")
    void publishedResolutionCarriesVersionContent() {
        // v1 content
        AgentProfile profile = newProfile("v1 prompt", null);

        // publish v2 with different content
        String modelCode = ensureModel("m-f0-pin-" + System.nanoTime());
        AgentProfileVersion draft = versionService.createDraft(profile.getId());
        versionService.updateDraft(draft.getId(), List.of("docker"), null, "v2 prompt", modelCode, null);
        AgentProfileVersion v2 = versionService.publish(profile.getId(), null);

        // the runtime resolution (reserve-time pin) resolves v2, not v1
        AgentProfileVersion resolved = versionService.requirePublished(profile.getId());
        assertThat(resolved.getId()).isEqualTo(v2.getId());
        assertThat(resolved.getSystemPrompt()).isEqualTo("v2 prompt");
        assertThat(resolved.getDefaultModel()).isEqualTo(modelCode);

        // and the archived v1 keeps its own (frozen) content
        List<AgentProfileVersion> versions = versionService.listVersions(profile.getId());
        AgentProfileVersion v1 = versions.stream()
                .filter(v -> v.getVersionNumber() == 1).findFirst().orElseThrow();
        assertThat(v1.getSystemPrompt()).isEqualTo("v1 prompt");
    }

    // ── session.open carries version content ──────────────────

    @Test
    @DisplayName("session.open pins the version and carries its default model config")
    void sessionOpenCarriesVersionContent() {
        String modelCode = ensureModel("m-f0-open-" + System.nanoTime());
        AgentProfile profile = newProfile("v1 prompt", modelCode);

        ai.myrmec.engine.project.dto.CreateProjectRequest projectReq =
                new ai.myrmec.engine.project.dto.CreateProjectRequest();
        projectReq.setName("f0-sess-" + System.nanoTime());
        projectReq.setDescription("Feature 0 session test");
        UUID projectId = projectService.create(projectReq).getId();

        AgentProfileVersion published = versionService.requirePublished(profile.getId());
        SessionOpenPayload open = sessionContextAssembler.assemble(
                "CONVERSATION", UUID.randomUUID(), projectId, profile.getId());

        // §9.5: the pin is the real published version row ID.
        assertThat(open.profileVersionId()).isEqualTo(published.getId());
        // model config resolved from the published version's default model
        assertThat(open.model()).isNotNull();
        assertThat(open.model().modelId()).isEqualTo("test/" + modelCode);
    }
}