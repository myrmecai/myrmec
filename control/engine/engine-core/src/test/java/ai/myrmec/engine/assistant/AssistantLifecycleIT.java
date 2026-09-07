package ai.myrmec.engine.assistant;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.DraftConflictException;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Lifecycle integration tests for the Assistant entity (#92,
 * {@code docs/design/assistant-entity.md} §5): create + initial Draft + OWNER
 * grant, the publish gate (collect-all-failures), single-Draft contention,
 * no-op re-publish, MINOR/MAJOR bumps, and the stale-Draft gate.
 */
@Transactional
class AssistantLifecycleIT extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private AssistantService assistantService;

    @Autowired
    private AssistantVersionService versionService;

    @Autowired
    private AssistantRepository assistantRepository;

    @Autowired
    private AssistantVersionRepository versionRepository;

    @Autowired
    private ai.myrmec.engine.agent.AgentProfileVersionService agentProfileVersionService;

    @Autowired
    private AssistantGrantRepository grantRepository;

    @Test
    void createSeedsParentInitialDraftAndOwnerGrant() {
        Fixture fx = newAssistant();

        Assistant assistant = assistantRepository.findById(fx.assistantId).orElseThrow();
        assertThat(assistant.getName()).isEqualTo(fx.name);
        assertThat(assistant.getCurrentVersionId()).isNull();

        AssistantVersion draft = versionRepository
                .findByAssistantIdAndStatus(fx.assistantId, AssistantVersion.Status.DRAFT)
                .orElseThrow();
        assertThat(draft.getAgentProfileId()).isEqualTo(fx.profileId);
        assertThat(draft.getParentVersionId()).isNull();
        assertThat(draft.getUsableVia()).containsExactly("WEB_UI");

        assertThat(grantRepository.findByAssistantId(fx.assistantId))
                .anyMatch(g -> g.getPermission() == AssistantGrant.Permission.OWNER
                        && g.getPrincipalId().equals(TEST_ADMIN_ID.toString()));
    }

    @Test
    void publishHappyPathAssignsV1AndFlipsCurrentVersion() {
        Fixture fx = newAssistant();

        AssistantVersion published = versionService.publish(fx.assistantId, TEST_ADMIN_ID);

        assertThat(published.getStatus()).isEqualTo(AssistantVersion.Status.PUBLISHED);
        assertThat(published.getVersionNumber()).isEqualTo("1.0");
        assertThat(published.getBumpType()).isEqualTo(AssistantVersion.BumpType.MAJOR);
        assertThat(published.getPublishedBy()).isEqualTo(TEST_ADMIN_ID);

        Assistant assistant = assistantRepository.findById(fx.assistantId).orElseThrow();
        assertThat(assistant.getCurrentVersionId()).isEqualTo(published.getId());
    }

    @Test
    void publishStampsPinnedProfileVersion() {
        // §4.2 (archived assistant-entity.md): agent_profile_version_id is a
        // "Required Published version at publish time" — publish stamps the
        // bound Profile's currently published version row.
        Fixture fx = newAssistant();

        AssistantVersion published = versionService.publish(fx.assistantId, TEST_ADMIN_ID);

        assertThat(published.getAgentProfileVersionId()).isNotNull();
        assertThat(published.getAgentProfileVersionId()).isEqualTo(
                agentProfileVersionService.requirePublished(fx.profileId).getId());
    }

    @Test
    void publishGateCollectsAllFailures() {
        Fixture fx = newAssistant();

        AssistantVersion draft = draftOf(fx.assistantId);
        draft.setUsableVia(List.of());                       // reach failure
        draft.setDisabledTools(List.of("tool_not_in_profile")); // tools failure
        versionService.saveDraft(draft);

        BadRequestException ex = catchThrowableOfType(
                () -> versionService.publish(fx.assistantId, TEST_ADMIN_ID),
                BadRequestException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getDetails()).extracting("field")
                .contains("usableVia", "disabledTools");
    }

    @Test
    void secondOpenDraftConflictsWithSingleDraftInvariant() {
        Fixture fx = newAssistant();
        versionService.publish(fx.assistantId, TEST_ADMIN_ID);

        versionService.openDraft(fx.assistantId, TEST_ADMIN_ID); // first draft OK

        assertThatThrownBy(() -> versionService.openDraft(fx.assistantId, TEST_ADMIN_ID))
                .isInstanceOf(DraftConflictException.class)
                .extracting("kind").isEqualTo(DraftConflictException.Kind.SINGLE_DRAFT);
    }

    @Test
    void republishWithoutChangesRejected() {
        Fixture fx = newAssistant();
        versionService.publish(fx.assistantId, TEST_ADMIN_ID);

        versionService.openDraft(fx.assistantId, TEST_ADMIN_ID); // clone, no edits

        assertThatThrownBy(() -> versionService.publish(fx.assistantId, TEST_ADMIN_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("No changes to publish");
    }

    @Test
    void behaviorChangeProducesMinorBump() {
        Fixture fx = newAssistant();
        versionService.publish(fx.assistantId, TEST_ADMIN_ID);

        AssistantVersion draft = versionService.openDraft(fx.assistantId, TEST_ADMIN_ID);
        draft.setGreetingMessage("Hello from v1.1");
        versionService.saveDraft(draft);

        AssistantVersion republished = versionService.publish(fx.assistantId, TEST_ADMIN_ID);

        assertThat(republished.getBumpType()).isEqualTo(AssistantVersion.BumpType.MINOR);
        assertThat(republished.getVersionNumber()).isEqualTo("1.1");
    }

    @Test
    void hitlStrictProducesMajorBump() {
        Fixture fx = newAssistant();
        versionService.publish(fx.assistantId, TEST_ADMIN_ID);

        AssistantVersion draft = versionService.openDraft(fx.assistantId, TEST_ADMIN_ID);
        draft.setHitlOverrideMode(AssistantVersion.HitlOverrideMode.STRICT);
        versionService.saveDraft(draft);

        AssistantVersion republished = versionService.publish(fx.assistantId, TEST_ADMIN_ID);

        assertThat(republished.getBumpType()).isEqualTo(AssistantVersion.BumpType.MAJOR);
        assertThat(republished.getVersionNumber()).isEqualTo("2.0");
    }

    @Test
    void staleDraftPublishRejected() {
        Fixture fx = newAssistant();
        versionService.publish(fx.assistantId, TEST_ADMIN_ID);

        AssistantVersion draft = versionService.openDraft(fx.assistantId, TEST_ADMIN_ID);
        draft.setGreetingMessage("edit while a newer version lands");
        versionService.saveDraft(draft);

        // Simulate a concurrent publish moving current_version_id past the version
        // this Draft was forked from. Point at a real published version (FK-valid).
        Fixture other = newAssistant();
        AssistantVersion otherPublished = versionService.publish(other.assistantId, TEST_ADMIN_ID);
        Assistant assistant = assistantRepository.findById(fx.assistantId).orElseThrow();
        assistant.setCurrentVersionId(otherPublished.getId());
        assistantRepository.save(assistant);

        assertThatThrownBy(() -> versionService.publish(fx.assistantId, TEST_ADMIN_ID))
                .isInstanceOf(DraftConflictException.class)
                .extracting("kind").isEqualTo(DraftConflictException.Kind.STALE_DRAFT);
    }

    // ==================== Helpers ====================

    private Fixture newAssistant() {
        Project project = data.project().named("assistant-it").create();
        AgentProfile profile = data.agentProfile().named("assistant-it-profile").create();
        String name = "Support Assistant " + UUID.randomUUID();
        Assistant assistant = assistantService.createAssistant(
                project.getId(), name, "desc", profile.getId(), TEST_ADMIN_ID);
        return new Fixture(assistant.getId(), profile.getId(), name);
    }

    private AssistantVersion draftOf(UUID assistantId) {
        return versionRepository
                .findByAssistantIdAndStatus(assistantId, AssistantVersion.Status.DRAFT)
                .orElseThrow();
    }

    private record Fixture(UUID assistantId, UUID profileId, String name) {
    }
}
