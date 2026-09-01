// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.context;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.instruction.InstructionAsset;
import ai.myrmec.engine.instruction.InstructionAssetRepository;
import ai.myrmec.engine.instruction.InstructionAssetService;
import ai.myrmec.engine.instruction.InstructionAssetVersion;
import ai.myrmec.engine.instruction.InstructionAssetVersionRepository;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectRepository;
import ai.myrmec.engine.project.ProjectSetting;
import ai.myrmec.engine.project.ProjectSettingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RECON-09: Context overflow contract.
 *
 * <p>Verifies that when Org Required instruction assets alone exceed the
 * token budget, the assembly sets {@code contextOverflow=true} — never
 * silently truncating REQUIRED assets.
 *
 * <p>When OPTIONAL assets are dropped to fit the budget, {@code truncated=true}
 * but {@code contextOverflow=false}. When REQUIRED assets alone exceed the
 * budget, both {@code truncated=true} and {@code contextOverflow=true}.
 */
@Tag("RECON-09")
@Tag("SG4")
@DisplayName("RECON-09: Context Overflow Contract")
class ContextOverflowContractTest extends IntegrationTestBase {

    @Autowired
    private ContextBuilder contextBuilder;

    @Autowired
    private InstructionAssetService instructionAssetService;

    @Autowired
    private InstructionAssetRepository instructionAssetRepository;

    @Autowired
    private InstructionAssetVersionRepository instructionAssetVersionRepository;

    @Autowired
    private ProjectSettingRepository projectSettingRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Test
    @DisplayName("empty project: no overflow, no truncation")
    void emptyProjectNoOverflow() {
        Project project = projectRepository.save(
                ai.myrmec.engine.TestDataFactory.projectBuilder("recon09-empty-" + System.nanoTime()).build());

        var result = contextBuilder.assemble(
                project.getId(), null, "CONVERSATIONAL", null, null, 0);

        assertThat(result.contextOverflow()).isFalse();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    @DisplayName("OPTIONAL assets truncated when over budget, contextOverflow=false")
    void optionalTruncatedNoOverflow() {
        Project project = projectRepository.save(
                ai.myrmec.engine.TestDataFactory.projectBuilder("recon09-optional-" + System.nanoTime()).build());

        // Set governance profile to FLEXIBLE (allows org-scoped inline instructions)
        setGovernanceProfile("FLEXIBLE");

        // Set a very small token budget so the OPTIONAL asset is truncated
        setTokenBudget(project.getId(), 10);

        // Create an org-scoped OPTIONAL instruction asset with enough content to exceed budget
        var asset = instructionAssetService.create(
                "ORGANIZATION", null, "recon09-optional-asset-" + System.nanoTime(),
                "Test optional asset", "STANDARD", TEST_ADMIN_ID, "Test Admin");

        // Publish with availability=OPTIONAL and large content
        publishInstruction(asset.getId(), "OPTIONAL", "This is a very long instruction content ".repeat(100));

        // Enable the OPTIONAL asset via project binding (must be after creation)
        enableOptionalBinding(project.getId());

        var result = contextBuilder.assemble(
                project.getId(), null, "CONVERSATIONAL", null, null, 0);

        // The OPTIONAL asset should be truncated (not enough budget)
        // But contextOverflow should be false (no REQUIRED assets were dropped)
        assertThat(result.truncated()).isTrue();
        assertThat(result.contextOverflow()).isFalse();
    }

    @Test
    @DisplayName("REQUIRED assets exceeding budget set contextOverflow=true")
    void requiredExceedsBudgetSetsOverflow() {
        Project project = projectRepository.save(
                ai.myrmec.engine.TestDataFactory.projectBuilder("recon09-required-" + System.nanoTime()).build());

        // Set governance profile to FLEXIBLE
        setGovernanceProfile("FLEXIBLE");

        // Set a very small token budget
        setTokenBudget(project.getId(), 10);

        // Create an org-scoped REQUIRED instruction asset with enough content to exceed budget
        var asset = instructionAssetService.create(
                "ORGANIZATION", null, "recon09-required-asset-" + System.nanoTime(),
                "Test required asset", "STANDARD", TEST_ADMIN_ID, "Test Admin");

        // Publish with availability=REQUIRED and large content
        publishInstruction(asset.getId(), "REQUIRED", "This is a required instruction content ".repeat(100));

        var result = contextBuilder.assemble(
                project.getId(), null, "CONVERSATIONAL", null, null, 0);

        // The REQUIRED asset alone exceeds the budget → contextOverflow=true
        // The REQUIRED asset is kept (never truncated); contextOverflow signals the problem
        assertThat(result.contextOverflow()).isTrue();
        // The REQUIRED instruction should still be present (not dropped)
        assertThat(result.instructions()).isNotEmpty();
        assertThat(result.instructions().get(0).availability()).isEqualTo("REQUIRED");
    }

    // --- helpers ---

    @Autowired
    private ai.myrmec.engine.setting.SystemSettingService systemSettingService;

    @Autowired
    private ai.myrmec.engine.project.ProjectInstructionBindingRepository bindingRepository;

    private void setGovernanceProfile(String profile) {
        systemSettingService.update(
                ai.myrmec.engine.governance.GovernancePolicyResolver.GOVERNANCE_PROFILE_KEY,
                profile, null);
    }

    private void enableOptionalBinding(UUID projectId) {
        // Enable all org OPTIONAL instruction assets for this project
        // by creating a binding for each org-scoped asset
        var orgAssets = instructionAssetRepository.findByScopeAndProjectIdIsNull("ORGANIZATION");
        for (InstructionAsset asset : orgAssets) {
            ai.myrmec.engine.project.ProjectInstructionBinding binding =
                    new ai.myrmec.engine.project.ProjectInstructionBinding();
            binding.setProjectId(projectId);
            binding.setInstructionAssetId(asset.getId());
            binding.setEnabled(true);
            bindingRepository.save(binding);
        }
    }

    private void setTokenBudget(UUID projectId, int budget) {
        ProjectSetting setting = new ProjectSetting();
        setting.setProjectId(projectId);
        setting.setSettingKey("context_token_budget");
        setting.setValueType("STRING");
        setting.setSettingValue(String.valueOf(budget));
        projectSettingRepository.save(setting);
    }

    private void publishInstruction(UUID assetId, String availability, String content) {
        // Create a draft version with the given availability + content
        instructionAssetService.createDraft(
                assetId, "INLINE", java.util.Map.of("content", content),
                null, null, availability, 0, null,
                TEST_ADMIN_ID, "RECON-09 Test");
        // Publish the draft
        instructionAssetService.publishDraft(assetId, null, "RECON-09 Test");
    }
}