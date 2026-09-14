package ai.myrmec.engine.conversation;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.assistant.Assistant;
import ai.myrmec.engine.assistant.AssistantService;
import ai.myrmec.engine.assistant.AssistantVersion;
import ai.myrmec.engine.assistant.AssistantVersionService;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Conversation rewire tests for the Assistant entity (#92,
 * {@code docs/design/assistant-entity.md} §5.6): starting a conversation with
 * an {@code assistantId} pins the assistant's currently published version (and
 * its stamped profile version) onto the conversation under a row lock.
 */
@Transactional
class ConversationAssistantPinningIT extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private AssistantService assistantService;

    @Autowired
    private AssistantVersionService versionService;

    @Autowired
    private ai.myrmec.engine.agent.AgentProfileService agentProfileService;

    @Autowired
    private ai.myrmec.engine.agent.AgentProfileVersionService agentProfileVersionService;

    @Autowired
    private AgentHostInstanceRepository instanceRepository;

    @Test
    void createConversationPinsPublishedAssistantVersion() {
        Project project = data.project().named("conv-pin").create();
        AgentProfile profile = data.agentProfile().named("conv-pin-profile").create();
        Assistant assistant = assistantService.createAssistant(
                project.getId(), "Pinned Assistant", "desc", profile.getId(), TEST_ADMIN_ID);
        AssistantVersion published = versionService.publish(assistant.getId(), TEST_ADMIN_ID);

        Conversation conversation = conversationService.createConversation(
                project.getId(), TEST_ADMIN_ID, "chat", null, null, assistant.getId());

        assertThat(conversation.getAssistantId()).isEqualTo(assistant.getId());
        assertThat(conversation.getAssistantVersionId()).isEqualTo(published.getId());
        // §4.2/§5.6: publish stamps the bound Profile's published version and
        // the conversation pins it — the real version row ID, immutable for
        // the life of the session.
        assertThat(published.getAgentProfileVersionId()).isNotNull();
        assertThat(conversation.getAgentProfileVersionId())
                .isEqualTo(published.getAgentProfileVersionId());
    }

    @Test
    void profileRepublishDoesNotAffectPinnedSession() {
        Project project = data.project().named("conv-repub").create();
        AgentProfile profile = data.agentProfile().named("conv-repub-profile")
                .withSystemPrompt("v1 prompt").create();
        Assistant assistant = assistantService.createAssistant(
                project.getId(), "Republish Assistant", "desc", profile.getId(), TEST_ADMIN_ID);
        AssistantVersion published = versionService.publish(assistant.getId(), TEST_ADMIN_ID);

        Conversation conversation = conversationService.createConversation(
                project.getId(), TEST_ADMIN_ID, "chat", null, null, assistant.getId());
        UUID pinnedProfileVersionId = conversation.getAgentProfileVersionId();

        // The Profile republishes a new version AFTER the session pinned v1.
        // (defaultModel lives on the version row — resolve the pinned one.)
        String defaultModel = agentProfileVersionService
                .requirePublished(profile.getId()).getDefaultModel();
        agentProfileService.updateProfile(profile.getId(), profile.getName(),
                profile.getDescription(), java.util.List.of("docker"), null, null,
                "v2 prompt", defaultModel);

        // The live session keeps its pinned version content (§16.1: later
        // publishes never affect an in-flight session).
        assertThat(conversation.getAgentProfileVersionId()).isEqualTo(pinnedProfileVersionId);
        assertThat(published.getAgentProfileVersionId()).isEqualTo(pinnedProfileVersionId);
        // and the profile's NEW published version is a different row
        var currentPublished = agentProfileVersionService.findPublished(profile.getId());
        assertThat(currentPublished).isPresent();
        assertThat(currentPublished.get().getId()).isNotEqualTo(pinnedProfileVersionId);
        assertThat(currentPublished.get().getSystemPrompt()).isEqualTo("v2 prompt");
    }

    @Test
    void createConversationResolvesHostByCapacityNotProfile() {
        Project project = data.project().named("conv-host").create();

        // Two unrelated profiles so that a profile-bound selector cannot
        // accidentally return the in-project host.
        AgentProfile unrelatedProfile = data.agentProfile().named("conv-host-unrelated").create();
        AgentProfile assistantProfile = data.agentProfile().named("conv-host-assistant").create();

        // An unrelated, live but unscoped host — must NOT be chosen just
        // because it shares a profile with the assistant.
        AgentHost unrelatedHost = data.agent().named("conv-host-unrelated-agent")
                .withProfile(unrelatedProfile).create().agent();
        // The in-project host shares the assistant's profile only by
        // coincidence; selection depends on capacity and project scope.
        AgentHostCreationResult inProjectResult = data.agent().named("conv-host-inproject-agent")
                .withProfile(assistantProfile).inProject(project).create();
        AgentHost inProjectHost = inProjectResult.agent();

        openInstance(unrelatedHost);
        openInstance(inProjectHost);

        Assistant assistant = assistantService.createAssistant(
                project.getId(), "Hosted Assistant", "desc", assistantProfile.getId(), TEST_ADMIN_ID);
        versionService.publish(assistant.getId(), TEST_ADMIN_ID);

        Conversation conversation = conversationService.createConversation(
                project.getId(), TEST_ADMIN_ID, "chat", null, null, assistant.getId());

        // §3.7: runtime host is selected by capacity (live + project scope),
        // never by profile binding. The in-project live host wins.
        assertThat(conversation.getAgentId()).isEqualTo(inProjectHost.getId());
    }

    @Test
    void createConversationWithoutPublishedVersionRejected() {
        Project project = data.project().named("conv-nopub").create();
        AgentProfile profile = data.agentProfile().named("conv-nopub-profile").create();
        Assistant assistant = assistantService.createAssistant(
                project.getId(), "Draft-only Assistant", "desc", profile.getId(), TEST_ADMIN_ID);

        assertThatThrownBy(() -> conversationService.createConversation(
                project.getId(), TEST_ADMIN_ID, "chat", null, null, assistant.getId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("no published version");
    }

    @Test
    void createConversationWithArchivedAssistantRejected() {
        Project project = data.project().named("conv-archived").create();
        AgentProfile profile = data.agentProfile().named("conv-archived-profile").create();
        Assistant assistant = assistantService.createAssistant(
                project.getId(), "Archived Assistant", "desc", profile.getId(), TEST_ADMIN_ID);
        versionService.publish(assistant.getId(), TEST_ADMIN_ID);
        assistantService.archive(assistant.getId());

        assertThatThrownBy(() -> conversationService.createConversation(
                project.getId(), TEST_ADMIN_ID, "chat", null, null, assistant.getId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("archived");
    }

    @Test
    void createConversationFromWrongProjectRejected() {
        Project project = data.project().named("conv-owner").create();
        Project otherProject = data.project().named("conv-other").create();
        AgentProfile profile = data.agentProfile().named("conv-wrongproj-profile").create();
        Assistant assistant = assistantService.createAssistant(
                project.getId(), "Owned Assistant", "desc", profile.getId(), TEST_ADMIN_ID);
        versionService.publish(assistant.getId(), TEST_ADMIN_ID);

        assertThatThrownBy(() -> conversationService.createConversation(
                otherProject.getId(), TEST_ADMIN_ID, "chat", null, null, assistant.getId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("does not belong");
    }

    @Test
    void createConversationWithoutAssistantLeavesPinsNull() {
        Project project = data.project().named("conv-legacy").create();

        Conversation conversation = conversationService.createConversation(
                project.getId(), TEST_ADMIN_ID, "legacy chat", null, null);

        assertThat(conversation.getAssistantId()).isNull();
        assertThat(conversation.getAssistantVersionId()).isNull();
        assertThat(conversation.getAgentProfileVersionId()).isNull();
    }

    private void openInstance(AgentHost host) {
        instanceRepository.saveAndFlush(AgentHostInstance.open(
                host, null, "dev-laptop", 1, java.util.Map.of("cpuCount", 2), "engine-node-1"));
    }

}
