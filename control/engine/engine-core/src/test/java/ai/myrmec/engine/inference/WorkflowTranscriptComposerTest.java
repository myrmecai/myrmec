// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference;

import ai.myrmec.engine.websocket.message.payload.InferenceMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WorkflowTranscriptComposer} — verifies byte-for-byte
 * parity with the agent's {@code assembleTask.ts} logic (R2 / §10.1).
 */
@DisplayName("WorkflowTranscriptComposer")
class WorkflowTranscriptComposerTest {

    private final WorkflowTranscriptComposer composer = new WorkflowTranscriptComposer();

    @Test
    @DisplayName("system prompt only, no knowledge")
    void testSystemPromptOnly() {
        InferenceRequestSpec spec = InferenceRequestSpec.builder()
                .serviceType("WORKFLOW")
                .systemPrompt("You are a helpful assistant.")
                .stepPrompt("Do the thing.")
                .build();

        List<InferenceMessage> messages = composer.compose(spec);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo("system");
        assertThat(messages.get(0).content()).isEqualTo("You are a helpful assistant.");
        assertThat(messages.get(1).role()).isEqualTo("user");
        assertThat(messages.get(1).content()).isEqualTo("Do the thing.");
    }

    @Test
    @DisplayName("system prompt + knowledge grouped by category in fixed order")
    void testKnowledgeGrouping() {
        InferenceRequestSpec spec = InferenceRequestSpec.builder()
                .serviceType("WORKFLOW")
                .systemPrompt("You are a helpful assistant.")
                .stepPrompt("Do the thing.")
                .knowledge(List.of(
                        new InferenceRequestSpec.KnowledgeEntry(
                                "Architecture Doc", "Use microservices", "ARCHITECTURE"),
                        new InferenceRequestSpec.KnowledgeEntry(
                                "Coding Standard", "Use 4-space indent", "STANDARD"),
                        new InferenceRequestSpec.KnowledgeEntry(
                                "API Spec", "REST + JSON", "REQUIREMENT"),
                        new InferenceRequestSpec.KnowledgeEntry(
                                "Deploy Guide", "Use Helm", "INSTRUCTION")
                ))
                .build();

        List<InferenceMessage> messages = composer.compose(spec);

        assertThat(messages).hasSize(2);
        String system = messages.get(0).content();
        // Order must be STANDARD → REQUIREMENT → ARCHITECTURE → INSTRUCTION
        int stdPos = system.indexOf("Standards & Conventions");
        int reqPos = system.indexOf("Requirements");
        int archPos = system.indexOf("Architecture");
        int instrPos = system.indexOf("Instructions");
        assertThat(stdPos).isLessThan(reqPos);
        assertThat(reqPos).isLessThan(archPos);
        assertThat(archPos).isLessThan(instrPos);
        assertThat(system).contains("### Coding Standard\nUse 4-space indent");
        assertThat(system).contains("### API Spec\nREST + JSON");
        assertThat(system).contains("### Architecture Doc\nUse microservices");
        assertThat(system).contains("### Deploy Guide\nUse Helm");
        assertThat(system).contains("# Project Context");
    }

    @Test
    @DisplayName("no system prompt, only knowledge")
    void testKnowledgeOnly() {
        InferenceRequestSpec spec = InferenceRequestSpec.builder()
                .serviceType("WORKFLOW")
                .stepPrompt("Do the thing.")
                .knowledge(List.of(
                        new InferenceRequestSpec.KnowledgeEntry(
                                "Standard A", "Content A", "STANDARD")
                ))
                .build();

        List<InferenceMessage> messages = composer.compose(spec);

        assertThat(messages).hasSize(2);
        // System message starts with "# Project Context" (no profile prompt)
        assertThat(messages.get(0).content()).startsWith("\n\n# Project Context");
        assertThat(messages.get(1).content()).isEqualTo("Do the thing.");
    }

    @Test
    @DisplayName("input.messages array — step prompt as leading user message, then each by role")
    void testInputMessagesArray() {
        InferenceRequestSpec spec = InferenceRequestSpec.builder()
                .serviceType("WORKFLOW")
                .systemPrompt("System prompt.")
                .stepPrompt("Step instruction.")
                .input(Map.of(
                        "messages", List.of(
                                Map.of("role", "user", "content", "Hello"),
                                Map.of("role", "assistant", "content", "Hi there"),
                                Map.of("role", "user", "content", "Do work")
                        )
                ))
                .build();

        List<InferenceMessage> messages = composer.compose(spec);

        // system, step prompt as user, then 3 chat messages
        assertThat(messages).hasSize(5);
        assertThat(messages.get(0).role()).isEqualTo("system");
        assertThat(messages.get(1).role()).isEqualTo("user");
        assertThat(messages.get(1).content()).isEqualTo("Step instruction.");
        assertThat(messages.get(2).role()).isEqualTo("user");
        assertThat(messages.get(2).content()).isEqualTo("Hello");
        assertThat(messages.get(3).role()).isEqualTo("assistant");
        assertThat(messages.get(3).content()).isEqualTo("Hi there");
        assertThat(messages.get(4).role()).isEqualTo("user");
        assertThat(messages.get(4).content()).isEqualTo("Do work");
    }

    @Test
    @DisplayName("input.prompt + other fields — step prompt + prompt + JSON block")
    void testInputPromptAndData() {
        InferenceRequestSpec spec = InferenceRequestSpec.builder()
                .serviceType("WORKFLOW")
                .systemPrompt("System prompt.")
                .stepPrompt("Step instruction.")
                .input(Map.of(
                        "prompt", "Additional context here",
                        "file", "test.java"
                ))
                .build();

        List<InferenceMessage> messages = composer.compose(spec);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo("system");
        // User message = step prompt + prompt + JSON block
        String userContent = messages.get(1).content();
        assertThat(userContent).contains("Step instruction.");
        assertThat(userContent).contains("Additional context here");
        assertThat(userContent).contains("## Input Data");
        assertThat(userContent).contains("```json");
        assertThat(userContent).contains("test.java");
    }

    @Test
    @DisplayName("no step prompt, no input — empty user message list")
    void testEmptyInput() {
        InferenceRequestSpec spec = InferenceRequestSpec.builder()
                .serviceType("WORKFLOW")
                .systemPrompt("System prompt.")
                .build();

        List<InferenceMessage> messages = composer.compose(spec);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).role()).isEqualTo("system");
        assertThat(messages.get(0).content()).isEqualTo("System prompt.");
    }

    @Test
    @DisplayName("empty knowledge list — no project context section")
    void testEmptyKnowledge() {
        InferenceRequestSpec spec = InferenceRequestSpec.builder()
                .serviceType("WORKFLOW")
                .systemPrompt("System prompt.")
                .stepPrompt("Do it.")
                .knowledge(List.of())
                .build();

        List<InferenceMessage> messages = composer.compose(spec);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).content()).isEqualTo("System prompt.");
        assertThat(messages.get(0).content()).doesNotContain("# Project Context");
    }
}