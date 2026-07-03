// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.context;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.instruction.InstructionAssetService;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for ContextBuilder â€” verifies context assembly
 * with instruction assets, governance profiles, token budget, and
 * context manifest recording.
 */
class ContextBuilderTest extends IntegrationTestBase {

    @Autowired
    private ContextBuilder contextBuilder;

    @Autowired
    private InstructionAssetService instructionAssetService;

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private ai.myrmec.engine.conversation.ConversationService conversationService;

    @Test
    void assemble_withNoInstructions_returnsEmptyContext() {
        Project project = data.project().named("ctx-empty-test").create();

        var result = contextBuilder.assemble(
                project.getId(), null, "CONVERSATION", null, null, 0);

        assertThat(result).isNotNull();
        assertThat(result.instructions()).isEmpty();
        assertThat(result.governanceProfileCode()).isEqualTo("STANDARD");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void assemble_withPublishedInstruction_includesIt() {
        // Create an instruction asset with INLINE content
        var asset = instructionAssetService.create(
                "ORGANIZATION", null, "test-ctx-instruction", "Test instruction",
                "STANDARD", TEST_ADMIN_ID, "Test Admin");

        instructionAssetService.createDraft(
                asset.getId(),
                "INLINE",
                Map.of("content", "Always respond in a professional tone."),
                null,
                Map.of("CONVERSATION", "true"),
                "REQUIRED",
                100,
                null,
                TEST_ADMIN_ID,
                "Test Admin");

        instructionAssetService.publishDraft(asset.getId(), TEST_ADMIN_ID, "Test Admin");

        // Assemble context
        var project = data.project().named("ctx-instr-test").create();
        var result = contextBuilder.assemble(
                project.getId(), null, "CONVERSATION", null, null, 0);

        assertThat(result.instructions()).hasSize(1);
        assertThat(result.instructions().get(0).name()).isEqualTo("test-ctx-instruction");
        assertThat(result.instructions().get(0).content()).isEqualTo("Always respond in a professional tone.");
        assertThat(result.instructions().get(0).sourceType()).isEqualTo("INLINE");
        assertThat(result.totalTokens()).isGreaterThan(0);
    }

    @Test
    void assemble_writesManifest_whenConversationIdProvided() {
        var asset = instructionAssetService.create(
                "ORGANIZATION", null, "test-manifest-asset", "Test",
                "PERSONA", TEST_ADMIN_ID, "Test Admin");

        instructionAssetService.createDraft(
                asset.getId(), "INLINE",
                Map.of("content", "You are a helpful assistant."),
                null, Map.of("CONVERSATION", "true"),
                "REQUIRED", 50, null,
                TEST_ADMIN_ID, "Test Admin");

        instructionAssetService.publishDraft(asset.getId(), TEST_ADMIN_ID, "Test Admin");

        var projectId = data.project().named("ctx-manifest-test").create().getId();
        var conversationId = createTestConversation(projectId);
        var result = contextBuilder.assemble(
                projectId, null, "CONVERSATION", conversationId, null, 1);

        assertThat(result.manifest()).isNotNull();
        assertThat(result.manifest().getConversationId()).isEqualTo(conversationId);
        assertThat(result.manifest().getSequenceNo()).isEqualTo(1L);
        assertThat(result.manifest().getGovernanceProfileCode()).isEqualTo("STANDARD");
        assertThat(result.manifest().getInstructionsIncluded()).hasSize(1);
    }

    @Test
    void assemble_appliesTokenBudget_truncatesLowPriority() {
        // Create a high-priority instruction
        var highAsset = instructionAssetService.create(
                "ORGANIZATION", null, "high-priority-instr", "High priority",
                "STANDARD", TEST_ADMIN_ID, "Test Admin");
        instructionAssetService.createDraft(
                highAsset.getId(), "INLINE",
                Map.of("content", "A".repeat(100)),
                null, Map.of("CONVERSATION", "true"),
                "REQUIRED", 500, null,
                TEST_ADMIN_ID, "Test Admin");
        instructionAssetService.publishDraft(highAsset.getId(), TEST_ADMIN_ID, "Test Admin");

        // Create a low-priority instruction with lots of content
        var lowAsset = instructionAssetService.create(
                "ORGANIZATION", null, "low-priority-instr", "Low priority",
                "STANDARD", TEST_ADMIN_ID, "Test Admin");
        instructionAssetService.createDraft(
                lowAsset.getId(), "INLINE",
                Map.of("content", "B".repeat(100000)),
                null, Map.of("CONVERSATION", "true"),
                "OPTIONAL", 1, null,
                TEST_ADMIN_ID, "Test Admin");
        instructionAssetService.publishDraft(lowAsset.getId(), TEST_ADMIN_ID, "Test Admin");

        var projectId = data.project().named("ctx-budget-test").create().getId();
        var result = contextBuilder.assemble(
                projectId, null, "CONVERSATION", null, null, 0);

        // High priority should be included, low priority truncated due to budget
        assertThat(result.instructions()).hasSize(1);
        assertThat(result.instructions().get(0).name()).isEqualTo("high-priority-instr");
        assertThat(result.truncated()).isTrue();
    }

    private java.util.UUID createTestConversation(java.util.UUID projectId) {
        var conversation = conversationService.createConversation(projectId, TEST_ADMIN_ID, null);
        return conversation.getId();
    }
}