// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.context;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.instruction.InstructionAsset;
import ai.myrmec.engine.instruction.InstructionAssetVersion;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for context pinning (Domain F).
 *
 * <p>Verifies that a {@link ContextSnapshot} is written to the conversation
 * when the governance profile has CONTEXT_PINNING=ON (PINNED_AT_START), and
 * that {@link ContextBuilder} reproduces the same instruction versions from
 * the snapshot even after a live republish.</p>
 */
class ContextPinningIT extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private ContextBuilder contextBuilder;

    private Project project;
    private InstructionAsset orgAsset;
    private InstructionAssetVersion orgV1;

    @BeforeEach
    void setUp() {
        // Use FLEXIBLE during setup so org-scoped INLINE instructions are allowed
        data.setDefaultGovernanceProfile("FLEXIBLE");
        project = data.project().named("pinning").create();
        orgAsset = data.instructionAsset()
                .named("org-rule")
                .scope("ORGANIZATION")
                .availability("REQUIRED")
                .content("Use metric units.")
                .create();
        orgV1 = instructionAssetVersionRepository
                .findByAssetIdAndStatus(orgAsset.getId(), "PUBLISHED")
                .orElseThrow();
    }

    @AfterEach
    void tearDown() {
        // Reset org default to STANDARD so other tests are not affected
        data.setDefaultGovernanceProfile("STANDARD");
    }

    @Test
    void pinnedAtStartWritesConversationSnapshot() {
        // STANDARD profile has CONTEXT_PINNING=ON → PINNED_AT_START
        data.setDefaultGovernanceProfile("STANDARD");

        Conversation conversation = data.conversation()
                .inProject(project.getId())
                .create();

        ContextSnapshot snapshot = conversation.getContextSnapshot();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.governanceProfileCode()).isEqualTo("STANDARD");
        assertThat(snapshot.instructionAssetVersionIds()).contains(orgV1.getId());
    }

    @Test
    void pinnedAtStartManifestReflectsSnapshotAfterRepublish() {
        // Set STANDARD to create the conversation with a pinned snapshot
        data.setDefaultGovernanceProfile("STANDARD");

        Conversation conversation = data.conversation()
                .inProject(project.getId())
                .create();
        ContextSnapshot snapshot = conversation.getContextSnapshot();
        assertThat(snapshot).isNotNull();

        // Switch to FLEXIBLE for the republish (STANDARD blocks org-scoped INLINE draft creation)
        data.setDefaultGovernanceProfile("FLEXIBLE");

        // Republish the org asset as v2
        InstructionAssetVersion orgV2 = data.instructionAssetVersion()
                .forAsset(orgAsset)
                .content("Use imperial units.")
                .publish();
        assertThat(orgV2.getId()).isNotEqualTo(orgV1.getId());

        // Assemble context with the pinned snapshot — should still use v1
        AssembledContext context = contextBuilder.assemble(
                project.getId(), null, "CONVERSATION",
                conversation.getId(), null, 1L, Map.of(), snapshot);

        assertThat(context.contextPinning()).isEqualTo("PINNED_AT_START");
        assertThat(context.instructions())
                .extracting(AssembledContext.InstructionEntry::versionId)
                .containsExactly(orgV1.getId());
    }

    @Test
    void immediateEffectDoesNotWriteSnapshot() {
        // FLEXIBLE profile has CONTEXT_PINNING=OFF → IMMEDIATE_EFFECT
        data.setDefaultGovernanceProfile("FLEXIBLE");

        Conversation conversation = data.conversation()
                .inProject(project.getId())
                .create();

        assertThat(conversation.getContextSnapshot()).isNull();
    }

    @Autowired
    private ai.myrmec.engine.instruction.InstructionAssetVersionRepository instructionAssetVersionRepository;
}