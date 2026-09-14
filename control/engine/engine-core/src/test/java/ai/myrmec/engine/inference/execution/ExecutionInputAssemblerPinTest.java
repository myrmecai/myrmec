// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.assistant.Assistant;
import ai.myrmec.engine.assistant.AssistantService;
import ai.myrmec.engine.assistant.AssistantVersion;
import ai.myrmec.engine.assistant.AssistantVersionService;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationMessage;
import ai.myrmec.engine.conversation.ConversationService;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.agent.AgentProfileVersion;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decoupling cutover's core contract (§3.7/§16.1): a conversation's
 * profile comes from the ASSISTANT PIN, never from the host, and the pinned
 * version row is immutable for the life of the conversation — a profile
 * republish must not change what the next turn's assembled input carries.
 */
class ExecutionInputAssemblerPinTest extends IntegrationTestBase {

    @Autowired TestDataBuilder data;
    @Autowired ConversationService conversationService;
    @Autowired AssistantService assistantService;
    @Autowired AssistantVersionService assistantVersionService;
    @Autowired ExecutionInputAssembler assembler;
    @Autowired ai.myrmec.engine.conversation.ConversationRepository conversationRepository;
    @Autowired ai.myrmec.engine.agent.AgentProfileService agentProfileService;
    @Autowired ai.myrmec.engine.agent.AgentProfileVersionService agentProfileVersionService;

    @Test
    void pinnedVersionSurvivesProfileRepublishInAssembledInput() {
        Project project = data.project().named("pin-republish").create();
        // v1 prompt is distinct and greppable.
        AgentProfile profile = data.agentProfile()
                .named("pin-republish-profile").withSystemPrompt("v1 PINNED PROMPT").create();

        Assistant assistant = assistantService.createAssistant(
                project.getId(), "Pin Assistant", "desc", profile.getId(), TEST_ADMIN_ID);
        AssistantVersion published = assistantVersionService.publish(assistant.getId(), TEST_ADMIN_ID);

        Conversation conversation = data.conversation().inProject(project).create();
        conversation.setAssistantId(assistant.getId());
        conversation.setAssistantVersionId(published.getId());
        conversation.setAgentProfileVersionId(published.getAgentProfileVersionId());
        conversationRepository.save(conversation);
        conversationService.appendMessage(conversation.getId(),
                ConversationMessage.Role.USER, "hello", TEST_ADMIN_ID, null);

        // Republish the profile AFTER the conversation pinned v1 (§16.1).
        String defaultModel = agentProfileVersionService
                .requirePublished(profile.getId()).getDefaultModel();
        agentProfileService.updateProfile(profile.getId(), profile.getName(),
                profile.getDescription(), java.util.List.of(), null, null,
                "v2 REPACKAGED PROMPT", defaultModel);

        // The assembled input for the next turn still carries v1's prompt.
        Map<String, Object> input = assembler.assembleConversationInput(
                conversation.getId(), UUID.randomUUID(), project.getId(), 1L);

        // The assembled input's "messages" is the legacy transcript — a
        // List<InferenceMessage> (P5 lesson: never cast it to List<Map>).
        @SuppressWarnings("unchecked")
        List<ai.myrmec.engine.websocket.message.payload.InferenceMessage> messages =
                (List<ai.myrmec.engine.websocket.message.payload.InferenceMessage>)
                        (Object) input.get("messages");
        String systemText = messages.stream()
                .filter(m -> "system".equalsIgnoreCase(String.valueOf(m.role())))
                .map(m -> String.valueOf(m.content()))
                .findFirst().orElse("");
        assertThat(systemText).contains("v1 PINNED PROMPT");
        assertThat(systemText).doesNotContain("v2 REPACKAGED PROMPT");

        // And the pinned version row is still the v1 row (not the new published one).
        AgentProfileVersion currentPublished =
                agentProfileVersionService.findPublished(profile.getId()).orElseThrow();
        assertThat(currentPublished.getSystemPrompt()).isEqualTo("v2 REPACKAGED PROMPT");
        assertThat(published.getAgentProfileVersionId())
                .isNotEqualTo(currentPublished.getId());
    }

    @Test
    void unpinnedConversationIsRejectedByTheAssembler() {
        Project project = data.project().named("pin-missing").create();
        Conversation conversation = data.conversation().inProject(project).create();
        conversationService.appendMessage(conversation.getId(),
                ConversationMessage.Role.USER, "hello", TEST_ADMIN_ID, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        assembler.assembleConversationInput(
                                conversation.getId(), UUID.randomUUID(), project.getId(), 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no pinned profile version");
    }
}