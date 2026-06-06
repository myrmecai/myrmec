package ai.myrmec.engine.conversation;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.user.AuthenticationProvider;
import ai.myrmec.engine.user.User;
import ai.myrmec.engine.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6a smoke test — proves the new schema + entities + service round
 * trip end-to-end.
 *
 * <p>This isn't the streaming protocol test (that's 6b/6c); it just
 * exercises {@link ConversationService} to validate the wiring is correct
 * before the broker layers on.</p>
 */
class ConversationServiceTest extends IntegrationTestBase {

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ConversationMessageRepository messageRepository;

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private UserRepository userRepository;

    private UUID seedUser(String emailSuffix) {
        User user = new User();
        user.setEmail("conv-test-" + emailSuffix + "@test.local");
        user.setName("Conv Test " + emailSuffix);
        user.setPasswordHash("$2a$10$dummy");
        user.setProviderCode(AuthenticationProvider.LOCAL_CODE);
        user.setIsActive(true);
        user.setIsSystem(false);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user).getId();
    }

    @Test
    void createConversationSeedsOwnerParticipantAndAppendsMonotonicMessages() {
        Project project = data.project().named("conv-test").create();
        Conversation conversation = conversationService.createConversation(
                project.getId(), TEST_ADMIN_ID, "Test session");

        // Owner participant gets seeded
        assertThat(conversation.getId()).isNotNull();
        assertThat(conversation.getStatus()).isEqualTo(Conversation.Status.ACTIVE);

        // First USER turn
        ConversationMessage userMsg = conversationService.appendMessage(
                conversation.getId(),
                ConversationMessage.Role.USER,
                "what does the disk usage look like?",
                TEST_ADMIN_ID,
                null);
        // Second ASSISTANT turn (synthetic agent id; FK is nullable)
        ConversationMessage assistantMsg = conversationService.appendMessage(
                conversation.getId(),
                ConversationMessage.Role.ASSISTANT,
                "Running df -h now.",
                null,
                null);

        assertThat(userMsg.getSequenceNo()).isEqualTo(0L);
        assertThat(assistantMsg.getSequenceNo()).isEqualTo(1L);

        List<ConversationMessage> messages =
                messageRepository.findByConversationIdOrderBySequenceNoAsc(conversation.getId());
        assertThat(messages).extracting(ConversationMessage::getRole)
                .containsExactly(
                        ConversationMessage.Role.USER,
                        ConversationMessage.Role.ASSISTANT);
        assertThat(messages).extracting(ConversationMessage::getSequenceNo)
                .containsExactly(0L, 1L);
    }

    @Test
    void addParticipantUpsertsExistingRole() {
        Project project = data.project().named("conv-acl").create();
        Conversation conversation = conversationService.createConversation(
                project.getId(), TEST_ADMIN_ID, "ACL test");

        UUID otherUser = seedUser(UUID.randomUUID().toString().substring(0, 8));
        ConversationParticipant first = conversationService.addParticipant(
                conversation.getId(), otherUser, ConversationParticipant.Role.VIEWER);
        ConversationParticipant updated = conversationService.addParticipant(
                conversation.getId(), otherUser, ConversationParticipant.Role.EDITOR);

        assertThat(updated.getId()).isEqualTo(first.getId());
        assertThat(updated.getRole()).isEqualTo(ConversationParticipant.Role.EDITOR);
    }
}
