// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.context;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.instruction.InstructionAssetService;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectInstructionBindingService;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
    private ProjectInstructionBindingService instructionBindingService;

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private ai.myrmec.engine.conversation.ConversationService conversationService;

    @Autowired
    private ai.myrmec.engine.governance.GovernanceProfileService governanceProfileService;

    @Autowired
    private ai.myrmec.engine.project.ProjectInstructionBindingRepository projectInstructionBindingRepository;

    /**
     * Several tests in this class create org-scoped INLINE instruction assets,
     * which the default STANDARD profile rejects (INLINE_INSTRUCTIONS_SCOPE =
     * PROJECT_SERVICE). Switch to FLEXIBLE (INLINE_INSTRUCTIONS_SCOPE = ALL)
     * so the asset-creation paths exercised here stay legal, then restore
     * STANDARD afterwards to keep the default profile clean for other suites.
     */
    @BeforeEach
    void useFlexibleGovernanceProfile() {
        governanceProfileService.setDefaultProfile("FLEXIBLE", null);
    }

    @AfterEach
    void restoreStandardGovernanceProfile() {
        governanceProfileService.setDefaultProfile("STANDARD", null);
    }

    @Test
    void assemble_withNoInstructions_returnsEmptyContext() {
        Project project = data.project().named("ctx-empty-test").create();

        var result = contextBuilder.assemble(
                project.getId(), null, "CONVERSATION", null, null, 0);

        assertThat(result).isNotNull();
        assertThat(result.instructions()).isEmpty();
        assertThat(result.governanceProfileCode()).isEqualTo("FLEXIBLE");
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
        assertThat(result.manifest().getSessionId()).isEqualTo(conversationId);
        assertThat(result.manifest().getSequenceNo()).isEqualTo(1L);
        assertThat(result.manifest().getGovernanceProfileCode()).isEqualTo("FLEXIBLE");
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
        // (OPTIONAL so it can be truncated when budget is exceeded;
        //  REQUIRED assets are never truncated — see RECON-09)
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
        // Enable the OPTIONAL low-priority asset for the project
        var orgAssets = instructionAssetRepository.findByScopeAndProjectIdIsNull("ORGANIZATION");
        for (var a : orgAssets) {
            if ("low-priority-instr".equals(a.getName())) {
                var binding = new ai.myrmec.engine.project.ProjectInstructionBinding();
                binding.setProjectId(projectId);
                binding.setInstructionAssetId(a.getId());
                binding.setEnabled(true);
                projectInstructionBindingRepository.save(binding);
            }
        }
        var result = contextBuilder.assemble(
                projectId, null, "CONVERSATION", null, null, 0);

        // High priority (REQUIRED) should be included, low priority (OPTIONAL) truncated due to budget
        assertThat(result.instructions()).hasSize(1);
        assertThat(result.instructions().get(0).name()).isEqualTo("high-priority-instr");
        assertThat(result.truncated()).isTrue();
        assertThat(result.contextOverflow()).isFalse();
    }

    // ── Availability + project binding tests ──────────────────────────

    /**
     * Org REQUIRED instruction asset is always included, even without a
     * project binding.
     */
    @Test
    void assemble_orgRequiredIncluded_withoutBinding() {
        var asset = instructionAssetService.create(
                "ORGANIZATION", null, "required-no-binding", "Required instruction",
                "STANDARD", TEST_ADMIN_ID, "Test Admin");
        instructionAssetService.createDraft(
                asset.getId(), "INLINE",
                Map.of("content", "Required content."),
                null, Map.of("CONVERSATION", "true"),
                "REQUIRED", 100, null,
                TEST_ADMIN_ID, "Test Admin");
        instructionAssetService.publishDraft(asset.getId(), TEST_ADMIN_ID, "Test Admin");

        var project = data.project().named("ctx-required-test").create();
        var result = contextBuilder.assemble(
                project.getId(), null, "CONVERSATION", null, null, 0);

        assertThat(result.instructions())
                .anySatisfy(e -> assertThat(e.name()).isEqualTo("required-no-binding"));
    }

    /**
     * Org OPTIONAL instruction asset is excluded when no project binding
     * exists.
     */
    @Test
    void assemble_orgOptionalExcluded_withoutBinding() {
        var asset = instructionAssetService.create(
                "ORGANIZATION", null, "optional-no-binding", "Optional instruction",
                "STANDARD", TEST_ADMIN_ID, "Test Admin");
        instructionAssetService.createDraft(
                asset.getId(), "INLINE",
                Map.of("content", "Optional content."),
                null, Map.of("CONVERSATION", "true"),
                "OPTIONAL", 100, null,
                TEST_ADMIN_ID, "Test Admin");
        instructionAssetService.publishDraft(asset.getId(), TEST_ADMIN_ID, "Test Admin");

        var project = data.project().named("ctx-optional-excluded-test").create();
        var result = contextBuilder.assemble(
                project.getId(), null, "CONVERSATION", null, null, 0);

        assertThat(result.instructions())
                .noneSatisfy(e -> assertThat(e.name()).isEqualTo("optional-no-binding"));
    }

    /**
     * Org OPTIONAL instruction asset is included when a project binding
     * with enabled=true exists.
     */
    @Test
    void assemble_orgOptionalIncluded_withEnabledBinding() {
        var asset = instructionAssetService.create(
                "ORGANIZATION", null, "optional-enabled-binding", "Optional instruction",
                "STANDARD", TEST_ADMIN_ID, "Test Admin");
        instructionAssetService.createDraft(
                asset.getId(), "INLINE",
                Map.of("content", "Optional enabled content."),
                null, Map.of("CONVERSATION", "true"),
                "OPTIONAL", 100, null,
                TEST_ADMIN_ID, "Test Admin");
        instructionAssetService.publishDraft(asset.getId(), TEST_ADMIN_ID, "Test Admin");

        var project = data.project().named("ctx-optional-enabled-test").create();
        instructionBindingService.upsert(project.getId(), asset.getId(), true);

        var result = contextBuilder.assemble(
                project.getId(), null, "CONVERSATION", null, null, 0);

        assertThat(result.instructions())
                .anySatisfy(e -> assertThat(e.name()).isEqualTo("optional-enabled-binding"));
    }

    /**
     * Org OPTIONAL instruction asset is excluded when a project binding
     * with enabled=false exists.
     */
    @Test
    void assemble_orgOptionalExcluded_withDisabledBinding() {
        var asset = instructionAssetService.create(
                "ORGANIZATION", null, "optional-disabled-binding", "Optional instruction",
                "STANDARD", TEST_ADMIN_ID, "Test Admin");
        instructionAssetService.createDraft(
                asset.getId(), "INLINE",
                Map.of("content", "Optional disabled content."),
                null, Map.of("CONVERSATION", "true"),
                "OPTIONAL", 100, null,
                TEST_ADMIN_ID, "Test Admin");
        instructionAssetService.publishDraft(asset.getId(), TEST_ADMIN_ID, "Test Admin");

        var project = data.project().named("ctx-optional-disabled-test").create();
        instructionBindingService.upsert(project.getId(), asset.getId(), false);

        var result = contextBuilder.assemble(
                project.getId(), null, "CONVERSATION", null, null, 0);

        assertThat(result.instructions())
                .noneSatisfy(e -> assertThat(e.name()).isEqualTo("optional-disabled-binding"));
    }

    // ── Priority ordering tests (Task 1) ──────────────────────────────

    @Test
    void assemble_priorityOrder_ascending() {
        // Low-priority org asset (priority 10 → effective 1010)
        var lowAsset = instructionAssetService.create(
                "ORGANIZATION", null, "aaa-low-priority", "Low",
                "STANDARD", TEST_ADMIN_ID, "Test Admin");
        instructionAssetService.createDraft(
                lowAsset.getId(), "INLINE",
                Map.of("content", "Low priority content."),
                null, Map.of("CONVERSATION", "true"),
                "REQUIRED", 10, null,
                TEST_ADMIN_ID, "Test Admin");
        instructionAssetService.publishDraft(lowAsset.getId(), TEST_ADMIN_ID, "Test Admin");

        // High-priority org asset (priority 500 → effective 1500)
        var highAsset = instructionAssetService.create(
                "ORGANIZATION", null, "zzz-high-priority", "High",
                "STANDARD", TEST_ADMIN_ID, "Test Admin");
        instructionAssetService.createDraft(
                highAsset.getId(), "INLINE",
                Map.of("content", "High priority content."),
                null, Map.of("CONVERSATION", "true"),
                "REQUIRED", 500, null,
                TEST_ADMIN_ID, "Test Admin");
        instructionAssetService.publishDraft(highAsset.getId(), TEST_ADMIN_ID, "Test Admin");

        var project = data.project().named("ctx-priority-order-test").create();
        var result = contextBuilder.assemble(
                project.getId(), null, "CONVERSATION", null, null, 0);

        assertThat(result.instructions()).hasSize(2);
        // Ascending: low priority (1010) first, high priority (1500) second
        assertThat(result.instructions().get(0).name()).isEqualTo("aaa-low-priority");
        assertThat(result.instructions().get(1).name()).isEqualTo("zzz-high-priority");
    }

    @Test
    void assemble_samePriority_deterministicByName() {
        // Two assets with the same priority but names in reverse alphabetical order
        var assetZ = instructionAssetService.create(
                "ORGANIZATION", null, "zzz-same-priority", "Z",
                "STANDARD", TEST_ADMIN_ID, "Test Admin");
        instructionAssetService.createDraft(
                assetZ.getId(), "INLINE",
                Map.of("content", "Z content."),
                null, Map.of("CONVERSATION", "true"),
                "REQUIRED", 100, null,
                TEST_ADMIN_ID, "Test Admin");
        instructionAssetService.publishDraft(assetZ.getId(), TEST_ADMIN_ID, "Test Admin");

        var assetA = instructionAssetService.create(
                "ORGANIZATION", null, "aaa-same-priority", "A",
                "STANDARD", TEST_ADMIN_ID, "Test Admin");
        instructionAssetService.createDraft(
                assetA.getId(), "INLINE",
                Map.of("content", "A content."),
                null, Map.of("CONVERSATION", "true"),
                "REQUIRED", 100, null,
                TEST_ADMIN_ID, "Test Admin");
        instructionAssetService.publishDraft(assetA.getId(), TEST_ADMIN_ID, "Test Admin");

        var project = data.project().named("ctx-same-priority-test").create();
        var result = contextBuilder.assemble(
                project.getId(), null, "CONVERSATION", null, null, 0);

        assertThat(result.instructions()).hasSize(2);
        // Same priority → alphabetical by name: "aaa" before "zzz"
        assertThat(result.instructions().get(0).name()).isEqualTo("aaa-same-priority");
        assertThat(result.instructions().get(1).name()).isEqualTo("zzz-same-priority");
    }

    // ── Excluded-reason taxonomy tests (Task 2) ───────────────────────

    @Test
    void assemble_recordsCorrectExclusionReasons() {
        // DISABLED asset
        var disabledAsset = instructionAssetService.create(
                "ORGANIZATION", null, "disabled-asset", "Disabled",
                "STANDARD", TEST_ADMIN_ID, "Test Admin");
        instructionAssetService.createDraft(
                disabledAsset.getId(), "INLINE",
                Map.of("content", "Disabled content."),
                null, Map.of("CONVERSATION", "true"),
                "REQUIRED", 100, null,
                TEST_ADMIN_ID, "Test Admin");
        instructionAssetService.publishDraft(disabledAsset.getId(), TEST_ADMIN_ID, "Test Admin");
        instructionAssetService.disable(disabledAsset.getId(), TEST_ADMIN_ID, "Test Admin");

        // INCOMPLETE asset (no published version)
        var incompleteAsset = instructionAssetService.create(
                "ORGANIZATION", null, "incomplete-asset", "Incomplete",
                "STANDARD", TEST_ADMIN_ID, "Test Admin");
        // No draft or publish — stays INCOMPLETE

        // ARCHIVED asset (publish then archive)
        var archivedAsset = instructionAssetService.create(
                "ORGANIZATION", null, "archived-asset", "Archived",
                "STANDARD", TEST_ADMIN_ID, "Test Admin");
        instructionAssetService.createDraft(
                archivedAsset.getId(), "INLINE",
                Map.of("content", "Archived content."),
                null, Map.of("CONVERSATION", "true"),
                "REQUIRED", 100, null,
                TEST_ADMIN_ID, "Test Admin");
        instructionAssetService.publishDraft(archivedAsset.getId(), TEST_ADMIN_ID, "Test Admin");
        instructionAssetService.disable(archivedAsset.getId(), TEST_ADMIN_ID, "Test Admin");
        instructionAssetService.archive(archivedAsset.getId(), TEST_ADMIN_ID, "Test Admin");

        var projectId = data.project().named("ctx-exclusion-reasons-test").create().getId();
        var conversationId = createTestConversation(projectId);
        var result = contextBuilder.assemble(
                projectId, null, "CONVERSATION", conversationId, null, 1);

        assertThat(result.manifest()).isNotNull();
        var excluded = result.manifest().getInstructionsExcluded();
        assertThat(excluded).isNotNull();

        // Verify each exclusion reason
        assertThat(excluded).anySatisfy(m -> {
            assertThat(m.get("name")).isEqualTo("disabled-asset");
            assertThat(m.get("reason")).isEqualTo("DISABLED");
        });
        assertThat(excluded).anySatisfy(m -> {
            assertThat(m.get("name")).isEqualTo("incomplete-asset");
            assertThat(m.get("reason")).isEqualTo("INCOMPLETE");
        });
        assertThat(excluded).anySatisfy(m -> {
            assertThat(m.get("name")).isEqualTo("archived-asset");
            assertThat(m.get("reason")).isEqualTo("ARCHIVED");
        });
    }

    // ── Applicability filtering tests (Task 3) ────────────────────────

    @Test
    void assemble_applicability_filtersByServiceType() {
        // CONVERSATION-only asset
        var convAsset = instructionAssetService.create(
                "ORGANIZATION", null, "conv-only-asset", "Conv only",
                "STANDARD", TEST_ADMIN_ID, "Test Admin");
        instructionAssetService.createDraft(
                convAsset.getId(), "INLINE",
                Map.of("content", "Conversation content."),
                null, Map.of("CONVERSATION", "true"),
                "REQUIRED", 100, null,
                TEST_ADMIN_ID, "Test Admin");
        instructionAssetService.publishDraft(convAsset.getId(), TEST_ADMIN_ID, "Test Admin");

        var project = data.project().named("ctx-applicability-test").create();

        // Assemble with WORKFLOW service type → conv-only should be excluded
        var wfResult = contextBuilder.assemble(
                project.getId(), null, "WORKFLOW", null, null, 0);
        assertThat(wfResult.instructions())
                .noneSatisfy(e -> assertThat(e.name()).isEqualTo("conv-only-asset"));

        // Assemble with CONVERSATION service type → conv-only should be included
        var convResult = contextBuilder.assemble(
                project.getId(), null, "CONVERSATION", null, null, 0);
        assertThat(convResult.instructions())
                .anySatisfy(e -> assertThat(e.name()).isEqualTo("conv-only-asset"));
    }

    // ── Activation rules tests (Task 4) ───────────────────────────────

    @Test
    void assemble_activationRules_fileTypeMatch() {
        var sqlAsset = instructionAssetService.create(
                "ORGANIZATION", null, "sql-activation-asset", "SQL activation",
                "STANDARD", TEST_ADMIN_ID, "Test Admin");
        instructionAssetService.createDraft(
                sqlAsset.getId(), "INLINE",
                Map.of("content", "SQL safety rules."),
                null, Map.of("CONVERSATION", "true"),
                "REQUIRED", 100,
                Map.of("fileType", "SQL"),
                TEST_ADMIN_ID, "Test Admin");
        instructionAssetService.publishDraft(sqlAsset.getId(), TEST_ADMIN_ID, "Test Admin");

        var project = data.project().named("ctx-activation-test").create();

        // fileType=SQL → included
        var sqlResult = contextBuilder.assemble(
                project.getId(), null, "CONVERSATION", null, null, 0,
                Map.of("fileType", "SQL"));
        assertThat(sqlResult.instructions())
                .anySatisfy(e -> assertThat(e.name()).isEqualTo("sql-activation-asset"));

        // fileType=PYTHON → excluded with ACTIVATION_NO_MATCH
        var pyResult = contextBuilder.assemble(
                project.getId(), null, "CONVERSATION", null, null, 0,
                Map.of("fileType", "PYTHON"));
        assertThat(pyResult.instructions())
                .noneSatisfy(e -> assertThat(e.name()).isEqualTo("sql-activation-asset"));

        // No fileType in context → excluded (no match)
        var noCtxResult = contextBuilder.assemble(
                project.getId(), null, "CONVERSATION", null, null, 0, null);
        assertThat(noCtxResult.instructions())
                .noneSatisfy(e -> assertThat(e.name()).isEqualTo("sql-activation-asset"));
    }

    private java.util.UUID createTestConversation(java.util.UUID projectId) {
        var conversation = conversationService.createConversation(projectId, TEST_ADMIN_ID, null);
        return conversation.getId();
    }
}