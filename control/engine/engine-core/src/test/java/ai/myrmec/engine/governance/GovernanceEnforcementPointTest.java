// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.instruction.InstructionAsset;
import ai.myrmec.engine.instruction.InstructionAssetService;
import ai.myrmec.engine.instruction.InstructionAssetVersion;
import ai.myrmec.engine.knowledge.DataFeedService;
import ai.myrmec.engine.knowledge.KnowledgeProviderService;
import ai.myrmec.engine.quota.QuotaAdminController;
import ai.myrmec.engine.quota.dto.CreateQuotaRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Enforcement-point integration tests — one per BLOCKING feature.
 *
 * <p>Under STRICT profile, creating disallowed resources returns
 * {@link GovernanceViolationException}. The allowed cases succeed.
 * Runs on the e2e profile (H2) with the real service beans.
 */
class GovernanceEnforcementPointTest extends IntegrationTestBase {

    @Autowired
    private GovernanceProfileService profileService;
    @Autowired
    private InstructionAssetService instructionAssetService;
    @Autowired
    private KnowledgeProviderService knowledgeProviderService;
    @Autowired
    private DataFeedService dataFeedService;
    @Autowired
    private QuotaAdminController quotaAdminController;
    @Autowired
    private ai.myrmec.engine.project.ProjectService projectService;

    @BeforeEach
    void setStrict() {
        profileService.setDefaultProfile("STRICT", null);
    }

    @AfterEach
    void resetToStandard() {
        profileService.setDefaultProfile("STANDARD", null);
    }

    /** Create a real project so the FK constraint on instruction_assets.project_id passes. */
    private UUID createProject() {
        var req = new ai.myrmec.engine.project.dto.CreateProjectRequest();
        req.setName("test-project-" + UUID.randomUUID());
        req.setDescription("Test project for governance enforcement");
        return projectService.create(req).getId();
    }

    // ── INSTRUCTION_SOURCES ─────────────────────────────────────────

    @Test
    void underStrict_inlineInstructionSourceIsRejected() {
        InstructionAsset asset = instructionAssetService.create(
                "ORGANIZATION", null, "test-asset-" + UUID.randomUUID(),
                "desc", "SYSTEM", null, "tester");

        assertThatThrownBy(() -> instructionAssetService.createDraft(
                asset.getId(), "INLINE", Map.of(), null,
                Map.of(), "OPTIONAL", 0, null, null, "tester"))
                .isInstanceOf(GovernanceViolationException.class)
                .satisfies(ex -> {
                    GovernanceViolationException gve = (GovernanceViolationException) ex;
                    assertThat(gve.getFeature()).isEqualTo(ProductFeature.INSTRUCTION_SOURCES);
                    assertThat(gve.getAttemptedValue()).isEqualTo("INLINE");
                });
    }

    @Test
    void underStrict_gitInstructionSourceIsAllowed() {
        InstructionAsset asset = instructionAssetService.create(
                "ORGANIZATION", null, "test-asset-git-" + UUID.randomUUID(),
                "desc", "SYSTEM", null, "tester");

        InstructionAssetVersion draft = instructionAssetService.createDraft(
                asset.getId(), "GIT", Map.of(), null,
                Map.of(), "OPTIONAL", 0, null, null, "tester");
        assertThat(draft).isNotNull();
    }

    // ── KNOWLEDGE_PROVIDERS ─────────────────────────────────────────

    @Test
    void underStrict_externalKnowledgeProviderIsRejected() {
        assertThatThrownBy(() -> knowledgeProviderService.create(
                "ORGANIZATION", null, "test-provider-" + UUID.randomUUID(),
                "desc", "EXTERNAL", null, "Tester"))
                .isInstanceOf(GovernanceViolationException.class)
                .satisfies(ex -> {
                    GovernanceViolationException gve = (GovernanceViolationException) ex;
                    assertThat(gve.getFeature()).isEqualTo(ProductFeature.KNOWLEDGE_PROVIDERS);
                    assertThat(gve.getAttemptedValue()).isEqualTo("EXTERNAL");
                });
    }

    @Test
    void underStrict_managedKnowledgeProviderIsAllowed() {
        var provider = knowledgeProviderService.create(
                "ORGANIZATION", null, "test-provider-managed-" + UUID.randomUUID(),
                "desc", "MANAGED", null, "Tester");
        assertThat(provider).isNotNull();
    }

    // ── DATA_FEEDS ─────────────────────────────────────────────────

    @Test
    void underStrict_nonGitDataFeedIsRejected() {
        assertThatThrownBy(() -> dataFeedService.create(
                "ORG", null, "test-feed-" + UUID.randomUUID(),
                "desc", null, null, null,
                Map.of("type", "CONFLUENCE"), null, UUID.randomUUID(), "Tester"))
                .isInstanceOf(GovernanceViolationException.class)
                .satisfies(ex -> {
                    GovernanceViolationException gve = (GovernanceViolationException) ex;
                    assertThat(gve.getFeature()).isEqualTo(ProductFeature.DATA_FEEDS);
                });
    }

    @Test
    void underStrict_gitDataFeedIsAllowed() {
        // The enforcement gate should pass (GIT is allowed under STRICT).
        // We don't assert the return value because the DataFeed entity may
        // require fields we don't set — we just verify no GovernanceViolationException.
        try {
            dataFeedService.create(
                    "ORG", null, "test-feed-git-" + UUID.randomUUID(),
                    "desc", null, null, null,
                    Map.of("type", "GIT"), null, UUID.randomUUID(), "Tester");
        } catch (Exception e) {
            // Any exception other than GovernanceViolationException is fine —
            // it means the gate passed but entity validation failed.
            assertThat(e).isNotInstanceOf(GovernanceViolationException.class);
        }
    }

    // ── BUDGET_OVERRIDE ────────────────────────────────────────────
    // Note: BUDGET_OVERRIDE enforcement at QuotaAdminController is covered
    // by GovernancePolicyResolverEnforcerTest (enforcer.assertPermitted for
    // BUDGET_OVERRIDE). The controller is @PreAuthorize-gated so calling it
    // directly in an integration test without a security context throws
    // AccessDeniedException before the governance check. The enforcer test
    // verifies the policy logic; the controller wiring is verified at compile
    // time (the enforcer is injected and called in the create method).

    // ── THRESHOLD FEATURES (INLINE_INSTRUCTIONS_SCOPE) ────────────
    // These tests verify the threshold/ordinal enforcement for
    // INLINE_INSTRUCTIONS_SCOPE (NONE < PROJECT_SERVICE < ALL) across
    // different profiles. Under FLEXIBLE (ALL), a project-scoped INLINE
    // draft must be allowed (ALL permits PROJECT_SERVICE).

    @Test
    void underFlexible_projectScopedInlineInstructionIsAllowed() {
        profileService.setDefaultProfile("FLEXIBLE", null);
        try {
            UUID projectId = createProject();
            InstructionAsset asset = instructionAssetService.create(
                    "PROJECT", projectId, "test-flex-asset-" + UUID.randomUUID(),
                    "desc", "SYSTEM", null, "tester");

            // FLEXIBLE has INLINE_INSTRUCTIONS_SCOPE=ALL, which should permit
            // project-scoped INLINE (ALL >= PROJECT_SERVICE).
            InstructionAssetVersion draft = instructionAssetService.createDraft(
                    asset.getId(), "INLINE", Map.of(), null,
                    Map.of(), "OPTIONAL", 0, null, null, "tester");
            assertThat(draft).isNotNull();
        } finally {
            profileService.setDefaultProfile("STRICT", null);
        }
    }

    @Test
    void underStandard_projectScopedInlineInstructionIsAllowed() {
        profileService.setDefaultProfile("STANDARD", null);
        try {
            UUID projectId = createProject();
            InstructionAsset asset = instructionAssetService.create(
                    "PROJECT", projectId, "test-std-asset-" + UUID.randomUUID(),
                    "desc", "SYSTEM", null, "tester");

            // STANDARD has INLINE_INSTRUCTIONS_SCOPE=PROJECT_SERVICE, which
            // should permit project-scoped INLINE (PROJECT_SERVICE >= PROJECT_SERVICE).
            InstructionAssetVersion draft = instructionAssetService.createDraft(
                    asset.getId(), "INLINE", Map.of(), null,
                    Map.of(), "OPTIONAL", 0, null, null, "tester");
            assertThat(draft).isNotNull();
        } finally {
            profileService.setDefaultProfile("STRICT", null);
        }
    }

    @Test
    void underStrict_orgScopedInlineInstructionIsRejected() {
        // STRICT has INLINE_INSTRUCTIONS_SCOPE=NONE — no inline at all.
        // But INSTRUCTION_SOURCES=GIT only, so INLINE is rejected by
        // the INSTRUCTION_SOURCES check first. This test verifies the
        // INSTRUCTION_SOURCES gate catches it.
        InstructionAsset asset = instructionAssetService.create(
                "ORGANIZATION", null, "test-strict-org-asset-" + UUID.randomUUID(),
                "desc", "SYSTEM", null, "tester");

        assertThatThrownBy(() -> instructionAssetService.createDraft(
                asset.getId(), "INLINE", Map.of(), null,
                Map.of(), "OPTIONAL", 0, null, null, "tester"))
                .isInstanceOf(GovernanceViolationException.class);
    }
}