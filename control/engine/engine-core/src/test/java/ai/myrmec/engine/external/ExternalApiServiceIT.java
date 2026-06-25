package ai.myrmec.engine.external;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine._system.security.ServiceAccountPrincipal;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.assistant.Assistant;
import ai.myrmec.engine.assistant.AssistantGrant;
import ai.myrmec.engine.assistant.AssistantService;
import ai.myrmec.engine.assistant.AssistantVersion;
import ai.myrmec.engine.assistant.AssistantVersionService;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationMessage;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.serviceaccount.ServiceAccount;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service-level integration tests for the External API (#95): the #95 double
 * gate (USE grant ∩ {@code EXTERNAL_API} reach) in {@link ExternalAssistantService}
 * and the service-account conversation boundary in {@link ExternalConversationService}.
 *
 * <p>Exercises the business logic directly (no HTTP / Keycloak chain — that is
 * proven separately by {@code ExternalApiChainIT}).</p>
 */
@Transactional
class ExternalApiServiceIT extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private AssistantService assistantService;

    @Autowired
    private AssistantVersionService versionService;

    @Autowired
    private ExternalAssistantService externalAssistantService;

    @Autowired
    private ExternalConversationService externalConversationService;

    // ==================== Discovery / double gate ====================

    @Test
    void discoveryReturnsOnlyDoublyGatedAssistants() {
        Project project = data.project().named("ext-disco").create();
        AgentProfile profile = data.agentProfile().named("ext-disco-profile").create();
        ServiceAccount sa = serviceAccount(project);

        // (a) usable: EXTERNAL_API reach + USE grant.
        UUID usable = publishedAssistant(project, profile, "Usable", true);
        grantUse(usable, sa);
        // (b) USE grant but WEB_UI-only reach -> excluded by reach gate.
        UUID webOnly = publishedAssistant(project, profile, "WebOnly", false);
        grantUse(webOnly, sa);
        // (c) EXTERNAL_API reach but no grant -> excluded by grant gate.
        publishedAssistant(project, profile, "Ungranted", true);

        List<ExternalAssistantService.UsableAssistant> result =
                externalAssistantService.listUsable(sa.getId(), project.getId());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).assistant().getId()).isEqualTo(usable);
    }

    @Test
    void requireUsableRejectsUngrantedAssistantAs404() {
        Project project = data.project().named("ext-gate").create();
        AgentProfile profile = data.agentProfile().named("ext-gate-profile").create();
        ServiceAccount sa = serviceAccount(project);
        UUID ungranted = publishedAssistant(project, profile, "NoGrant", true);

        assertThatThrownBy(() -> externalAssistantService.requireUsable(
                sa.getId(), project.getId(), ungranted))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void requireUsableRejectsWebOnlyReachAs404() {
        Project project = data.project().named("ext-reach").create();
        AgentProfile profile = data.agentProfile().named("ext-reach-profile").create();
        ServiceAccount sa = serviceAccount(project);
        UUID webOnly = publishedAssistant(project, profile, "WebOnly", false);
        grantUse(webOnly, sa);

        assertThatThrownBy(() -> externalAssistantService.requireUsable(
                sa.getId(), project.getId(), webOnly))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ==================== Conversation lifecycle ====================

    @Test
    void startConversationCreatesExternalConversationWithProvenance() {
        Project project = data.project().named("ext-start").create();
        AgentProfile profile = data.agentProfile().named("ext-start-profile").create();
        ServiceAccount sa = serviceAccount(project);
        ServiceAccountPrincipal principal = principal(sa, project);
        UUID assistantId = publishedAssistant(project, profile, "Starter", true);
        grantUse(assistantId, sa);

        Conversation conversation = externalConversationService.startConversation(
                principal, assistantId, "end-user-42", "hello");

        assertThat(conversation.getSource()).isEqualTo(Conversation.Source.EXTERNAL_API);
        assertThat(conversation.getExternalUserRef()).isEqualTo("end-user-42");
        assertThat(conversation.getServiceAccountId()).isEqualTo(sa.getId());
        assertThat(conversation.getAssistantId()).isEqualTo(assistantId);

        List<ConversationMessage> messages =
                externalConversationService.listMessages(principal, conversation.getId(), null);
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getRole()).isEqualTo(ConversationMessage.Role.USER);
        assertThat(messages.get(0).getContent()).isEqualTo("hello");
    }

    @Test
    void startConversationRequiresEndUserRef() {
        Project project = data.project().named("ext-noref").create();
        AgentProfile profile = data.agentProfile().named("ext-noref-profile").create();
        ServiceAccount sa = serviceAccount(project);
        ServiceAccountPrincipal principal = principal(sa, project);
        UUID assistantId = publishedAssistant(project, profile, "NoRef", true);
        grantUse(assistantId, sa);

        assertThatThrownBy(() -> externalConversationService.startConversation(
                principal, assistantId, "  ", "hi"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void startConversationRejectsUngrantedAssistant() {
        Project project = data.project().named("ext-start-gate").create();
        AgentProfile profile = data.agentProfile().named("ext-start-gate-profile").create();
        ServiceAccount sa = serviceAccount(project);
        ServiceAccountPrincipal principal = principal(sa, project);
        UUID assistantId = publishedAssistant(project, profile, "Locked", true);
        // no USE grant

        assertThatThrownBy(() -> externalConversationService.startConversation(
                principal, assistantId, "end-user", "hi"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void postAndListMessagesHonourSinceCursor() {
        Project project = data.project().named("ext-msgs").create();
        AgentProfile profile = data.agentProfile().named("ext-msgs-profile").create();
        ServiceAccount sa = serviceAccount(project);
        ServiceAccountPrincipal principal = principal(sa, project);
        UUID assistantId = publishedAssistant(project, profile, "Chatter", true);
        grantUse(assistantId, sa);

        Conversation conversation = externalConversationService.startConversation(
                principal, assistantId, "end-user", null);
        externalConversationService.postMessage(principal, conversation.getId(), "first");
        externalConversationService.postMessage(principal, conversation.getId(), "second");

        List<ConversationMessage> all =
                externalConversationService.listMessages(principal, conversation.getId(), null);
        assertThat(all).hasSize(2);

        List<ConversationMessage> afterFirst =
                externalConversationService.listMessages(principal, conversation.getId(), 0L);
        assertThat(afterFirst).hasSize(1);
        assertThat(afterFirst.get(0).getContent()).isEqualTo("second");
    }

    @Test
    void closeConversationArchivesAndBlocksFurtherMessages() {
        Project project = data.project().named("ext-close").create();
        AgentProfile profile = data.agentProfile().named("ext-close-profile").create();
        ServiceAccount sa = serviceAccount(project);
        ServiceAccountPrincipal principal = principal(sa, project);
        UUID assistantId = publishedAssistant(project, profile, "Closer", true);
        grantUse(assistantId, sa);

        Conversation conversation = externalConversationService.startConversation(
                principal, assistantId, "end-user", null);

        Conversation closed = externalConversationService.closeConversation(principal, conversation.getId());
        assertThat(closed.getStatus()).isEqualTo(Conversation.Status.ARCHIVED);

        assertThatThrownBy(() -> externalConversationService.postMessage(
                principal, conversation.getId(), "too late"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void crossServiceAccountCannotAccessConversation() {
        Project project = data.project().named("ext-cross").create();
        AgentProfile profile = data.agentProfile().named("ext-cross-profile").create();
        ServiceAccount owner = serviceAccount(project);
        ServiceAccount intruder = serviceAccount(project);
        ServiceAccountPrincipal ownerPrincipal = principal(owner, project);
        ServiceAccountPrincipal intruderPrincipal = principal(intruder, project);
        UUID assistantId = publishedAssistant(project, profile, "Private", true);
        grantUse(assistantId, owner);

        Conversation conversation = externalConversationService.startConversation(
                ownerPrincipal, assistantId, "end-user", null);

        assertThatThrownBy(() -> externalConversationService.getConversation(intruderPrincipal, conversation.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> externalConversationService.postMessage(
                intruderPrincipal, conversation.getId(), "peek"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> externalConversationService.listMessages(
                intruderPrincipal, conversation.getId(), null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listConversationsFiltersByExternalUserRef() {
        Project project = data.project().named("ext-list").create();
        AgentProfile profile = data.agentProfile().named("ext-list-profile").create();
        ServiceAccount sa = serviceAccount(project);
        ServiceAccountPrincipal principal = principal(sa, project);
        UUID assistantId = publishedAssistant(project, profile, "Lister", true);
        grantUse(assistantId, sa);

        externalConversationService.startConversation(principal, assistantId, "alice", null);
        externalConversationService.startConversation(principal, assistantId, "alice", null);
        externalConversationService.startConversation(principal, assistantId, "bob", null);

        assertThat(externalConversationService.listConversations(principal, "alice")).hasSize(2);
        assertThat(externalConversationService.listConversations(principal, "bob")).hasSize(1);
        assertThat(externalConversationService.listConversations(principal, null)).hasSize(3);
    }

    // ==================== Fixtures ====================

    /**
     * Create + publish an assistant, optionally opening its reach to
     * {@code EXTERNAL_API}. Returns the assistant id.
     */
    private UUID publishedAssistant(Project project, AgentProfile profile,
                                    String name, boolean externalApi) {
        Assistant assistant = assistantService.createAssistant(
                project.getId(), name, "desc", profile.getId(), TEST_ADMIN_ID);
        if (externalApi) {
            AssistantVersion draft = versionService.getOpenDraft(assistant.getId());
            draft.setUsableVia(new java.util.ArrayList<>(List.of("WEB_UI", "EXTERNAL_API")));
            versionService.saveDraft(draft);
        }
        versionService.publish(assistant.getId(), TEST_ADMIN_ID);
        return assistant.getId();
    }

    private void grantUse(UUID assistantId, ServiceAccount sa) {
        assistantService.addGrant(assistantId, AssistantGrant.PrincipalType.SERVICE_ACCOUNT,
                sa.getId().toString(), AssistantGrant.Permission.USE, TEST_ADMIN_ID);
    }

    private ServiceAccount serviceAccount(Project project) {
        ServiceAccount sa = new ServiceAccount();
        sa.setProjectId(project.getId());
        sa.setName("ext-sa-" + UUID.randomUUID());
        sa.setKeycloakClientId("client-" + UUID.randomUUID());
        sa.setEnabled(true);
        return serviceAccountRepository.save(sa);
    }

    private ServiceAccountPrincipal principal(ServiceAccount sa, Project project) {
        return new ServiceAccountPrincipal(
                sa.getId(), project.getId(), sa.getKeycloakClientId(), sa.getName());
    }
}
