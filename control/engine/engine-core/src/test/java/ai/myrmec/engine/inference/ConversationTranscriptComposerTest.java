// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference;

import ai.myrmec.engine.websocket.message.payload.InferenceMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ConversationTranscriptComposer} — verifies parity
 * with the agent's {@code assembleConversationMessages()} logic (R2 / §10.1).
 */
@DisplayName("ConversationTranscriptComposer")
class ConversationTranscriptComposerTest {

    private final ConversationTranscriptComposer composer = new ConversationTranscriptComposer();

    @Test
    @DisplayName("system prompt + pinned facts joined by \\n\\n")
    void testSystemPromptAndPinnedFacts() {
        InferenceRequestSpec spec = InferenceRequestSpec.builder()
                .serviceType("CONVERSATION")
                .conversationSystemPrompt("You are a helpful assistant.")
                .pinnedFacts("The project uses Java 21.")
                .userMessage("What is 2+2?")
                .build();

        List<InferenceMessage> messages = composer.compose(spec);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo("system");
        assertThat(messages.get(0).content()).isEqualTo("You are a helpful assistant.\n\nThe project uses Java 21.");
        assertThat(messages.get(1).role()).isEqualTo("user");
        assertThat(messages.get(1).content()).isEqualTo("What is 2+2?");
    }

    @Test
    @DisplayName("system prompt only, no pinned facts")
    void testSystemPromptOnly() {
        InferenceRequestSpec spec = InferenceRequestSpec.builder()
                .serviceType("CONVERSATION")
                .conversationSystemPrompt("Be concise.")
                .userMessage("Hello")
                .build();

        List<InferenceMessage> messages = composer.compose(spec);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).content()).isEqualTo("Be concise.");
    }

    @Test
    @DisplayName("pinned facts only, no system prompt")
    void testPinnedFactsOnly() {
        InferenceRequestSpec spec = InferenceRequestSpec.builder()
                .serviceType("CONVERSATION")
                .pinnedFacts("Always use tabs.")
                .userMessage("Code this")
                .build();

        List<InferenceMessage> messages = composer.compose(spec);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).content()).isEqualTo("Always use tabs.");
    }

    @Test
    @DisplayName("history entries followed by user message")
    void testHistoryAndUserMessage() {
        InferenceRequestSpec spec = InferenceRequestSpec.builder()
                .serviceType("CONVERSATION")
                .conversationSystemPrompt("System prompt.")
                .history(List.of(
                        new InferenceRequestSpec.HistoryEntry("user", "Hello"),
                        new InferenceRequestSpec.HistoryEntry("assistant", "Hi there!"),
                        new InferenceRequestSpec.HistoryEntry("user", "What is 2+2?")
                ))
                .userMessage("Actually, never mind.")
                .build();

        List<InferenceMessage> messages = composer.compose(spec);

        // system + 3 history + 1 user = 5
        assertThat(messages).hasSize(5);
        assertThat(messages.get(0).role()).isEqualTo("system");
        assertThat(messages.get(1).role()).isEqualTo("user");
        assertThat(messages.get(1).content()).isEqualTo("Hello");
        assertThat(messages.get(2).role()).isEqualTo("assistant");
        assertThat(messages.get(2).content()).isEqualTo("Hi there!");
        assertThat(messages.get(3).role()).isEqualTo("user");
        assertThat(messages.get(3).content()).isEqualTo("What is 2+2?");
        assertThat(messages.get(4).role()).isEqualTo("user");
        assertThat(messages.get(4).content()).isEqualTo("Actually, never mind.");
    }

    @Test
    @DisplayName("attachment context appended to user message")
    void testAttachmentContext() {
        InferenceRequestSpec spec = InferenceRequestSpec.builder()
                .serviceType("CONVERSATION")
                .conversationSystemPrompt("System prompt.")
                .userMessage("Review this file")
                .attachments(List.of(
                        new InferenceRequestSpec.AttachmentDescriptor(
                                "att-1", "config.yaml", "text/yaml", 500L,
                                "key: value", false, null)
                ))
                .build();

        List<InferenceMessage> messages = composer.compose(spec);

        assertThat(messages).hasSize(2);
        String userContent = messages.get(1).content();
        assertThat(userContent).contains("Review this file");
        assertThat(userContent).contains("--- Attached file: config.yaml (text/yaml) ---");
        assertThat(userContent).contains("key: value");
    }

    @Test
    @DisplayName("image attachment produces content parts")
    void testImageAttachmentParts() {
        InferenceRequestSpec spec = InferenceRequestSpec.builder()
                .serviceType("CONVERSATION")
                .conversationSystemPrompt("System prompt.")
                .userMessage("Look at this screenshot")
                .attachments(List.of(
                        new InferenceRequestSpec.AttachmentDescriptor(
                                "img-1", "screenshot.png", "image/png", 50000L,
                                null, true, "/api/v1/agent/attachments/img-1/content")
                ))
                .build();

        List<InferenceMessage> messages = composer.compose(spec);

        assertThat(messages).hasSize(2);
        InferenceMessage userMsg = messages.get(1);
        assertThat(userMsg.content()).contains("Look at this screenshot");
        assertThat(userMsg.content()).contains("--- Attached image: screenshot.png (image/png, 50000 bytes) ---");
        // Image parts should be present
        assertThat(userMsg.parts()).isNotNull();
        assertThat(userMsg.parts()).hasSize(1);
        assertThat(userMsg.parts().get(0).type()).isEqualTo("image");
        assertThat(userMsg.parts().get(0).attachmentId()).isEqualTo("img-1");
        assertThat(userMsg.parts().get(0).readContentPath()).isEqualTo("/api/v1/agent/attachments/img-1/content");
    }

    @Test
    @DisplayName("no system prompt, no history, no attachments — just user message")
    void testBareUserMessage() {
        InferenceRequestSpec spec = InferenceRequestSpec.builder()
                .serviceType("CONVERSATION")
                .userMessage("Hi")
                .build();

        List<InferenceMessage> messages = composer.compose(spec);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).role()).isEqualTo("user");
        assertThat(messages.get(0).content()).isEqualTo("Hi");
    }

    @Test
    @DisplayName("role normalization — unknown roles default to user")
    void testRoleNormalization() {
        InferenceRequestSpec spec = InferenceRequestSpec.builder()
                .serviceType("CONVERSATION")
                .conversationSystemPrompt("System.")
                .history(List.of(
                        new InferenceRequestSpec.HistoryEntry("User", "Upper case role"),
                        new InferenceRequestSpec.HistoryEntry("random", "Unknown role")
                ))
                .userMessage("Final message")
                .build();

        List<InferenceMessage> messages = composer.compose(spec);

        assertThat(messages).hasSize(4);
        assertThat(messages.get(1).role()).isEqualTo("user");
        assertThat(messages.get(2).role()).isEqualTo("user"); // "random" → normalized to "user"
    }
}