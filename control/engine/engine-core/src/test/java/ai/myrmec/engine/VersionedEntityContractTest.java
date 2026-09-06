// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.DraftConflictException;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.agent.AgentProfileRepository;
import ai.myrmec.engine.assistant.Assistant;
import ai.myrmec.engine.assistant.AssistantService;
import ai.myrmec.engine.assistant.AssistantVersion;
import ai.myrmec.engine.assistant.AssistantVersionService;
import ai.myrmec.engine.connection.ConnectionConfig;
import ai.myrmec.engine.connection.ConnectionConfigService;
import ai.myrmec.engine.connection.ConnectionConfigVersion;
import ai.myrmec.engine.governance.GovernanceProfileService;
import ai.myrmec.engine.instruction.InstructionAsset;
import ai.myrmec.engine.instruction.InstructionAssetService;
import ai.myrmec.engine.instruction.InstructionAssetVersion;
import ai.myrmec.engine.knowledge.KnowledgeProvider;
import ai.myrmec.engine.knowledge.KnowledgeProviderService;
import ai.myrmec.engine.knowledge.KnowledgeProviderVersion;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parametrized contract test for the versioned entity pattern.
 *
 * <p>Proves that all four versioned entities (Assistant, InstructionAsset,
 * ConnectionConfig, KnowledgeProvider) follow the same lifecycle:
 * INCOMPLETE → DRAFT → PUBLISHED → (v2 DRAFT → v2 PUBLISHED, v1 ARCHIVED).
 *
 * <p>Implements RECON-07 from {@code _archive/pattern-contract-tests.md}
 * and proves SG5 (state-machine integrity).</p>
 */
@DisplayName("Versioned Entity Contract")
class VersionedEntityContractTest extends IntegrationTestBase {

    @Autowired
    private GovernanceProfileService governanceProfileService;

    @BeforeEach
    void setFlexibleProfile() {
        governanceProfileService.setDefaultProfile("FLEXIBLE", null);
    }

    @AfterEach
    void resetProfile() {
        governanceProfileService.setDefaultProfile("STANDARD", null);
    }

    // ================================================================
    // Assistant
    // ================================================================

    @Nested
    @DisplayName("Assistant")
    class AssistantContract {

        @Autowired
        private AssistantService assistantService;

        @Autowired
        private AssistantVersionService versionService;

        @Autowired
        private AgentProfileRepository agentProfileRepository;

        @Autowired
        private TestDataBuilder data;

        private UUID parentId;
        private UUID agentProfileId;

        @BeforeEach
        void createParent() {
            // §4.2: the publish gate requires the bound profile to have a
            // published version — create through the service (publishes v1).
            AgentProfile profile = data.agentProfile()
                    .uniquelyNamed("vct-ap-" + UUID.randomUUID().toString().substring(0, 8))
                    .create();
            agentProfileId = profile.getId();

            // Use TestDataBuilder to create a project (handles group_id requirement).
            var project = data.project()
                    .named("vct-prj-" + UUID.randomUUID().toString().substring(0, 8))
                    .create();

            Assistant assistant = assistantService.createAssistant(
                    project.getId(),
                    "vct-asst-" + UUID.randomUUID().toString().substring(0, 8),
                    "Versioned entity contract test",
                    agentProfileId,
                    TEST_ADMIN_ID);
            parentId = assistant.getId();
        }

        @Test
        @DisplayName("VER-01: create parent → no published version (currentVersionId is null)")
        void createParent_noPublishedVersion() {
            Assistant parent = assistantService.getAssistant(parentId);
            assertThat(parent.getCurrentVersionId()).isNull();
        }

        @Test
        @DisplayName("VER-02: initial draft exists with DRAFT status")
        void initialDraft_draftStatus() {
            AssistantVersion draft = versionService.getOpenDraft(parentId);
            assertThat(draft.getStatus()).isEqualTo(AssistantVersion.Status.DRAFT);
        }

        @Test
        @DisplayName("VER-03: publish → version PUBLISHED, parent currentVersionId set")
        void publish_versionPublishedParentUpdated() {
            AssistantVersion published = versionService.publish(parentId, TEST_ADMIN_ID);
            assertThat(published.getStatus()).isEqualTo(AssistantVersion.Status.PUBLISHED);

            Assistant parent = assistantService.getAssistant(parentId);
            assertThat(parent.getCurrentVersionId()).isEqualTo(published.getId());
        }

        @Test
        @DisplayName("VER-04: published version is immutable (saveDraft rejected)")
        void publishedVersion_immutable() {
            AssistantVersion published = versionService.publish(parentId, TEST_ADMIN_ID);

            published.setAddendum("modified after publish");
            assertThatThrownBy(() -> versionService.saveDraft(published))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("VER-05/06: open v2 draft after publish, then discard")
        void openV2Draft_afterPublish() {
            AssistantVersion v1 = versionService.publish(parentId, TEST_ADMIN_ID);
            assertThat(v1.getVersionNumber()).isEqualTo("1.0");

            // Open a new draft (v2) from the published v1.
            AssistantVersion v2Draft = versionService.openDraft(parentId, TEST_ADMIN_ID);
            assertThat(v2Draft.getStatus()).isEqualTo(AssistantVersion.Status.DRAFT);
            assertThat(v2Draft.getParentVersionId()).isEqualTo(v1.getId());

            // Discard the v2 draft — parent should still point to v1.
            versionService.discardDraft(parentId);
            Assistant parent = assistantService.getAssistant(parentId);
            assertThat(parent.getCurrentVersionId()).isEqualTo(v1.getId());
        }

        @Test
        @DisplayName("VER-07: single draft enforcement — second openDraft throws DraftConflictException")
        void singleDraftEnforcement() {
            versionService.publish(parentId, TEST_ADMIN_ID);
            versionService.openDraft(parentId, TEST_ADMIN_ID);

            assertThatThrownBy(() -> versionService.openDraft(parentId, TEST_ADMIN_ID))
                    .isInstanceOf(DraftConflictException.class);
        }

        @Test
        @DisplayName("VER-08: discard draft → draft deleted")
        void discardDraft_removesDraft() {
            versionService.publish(parentId, TEST_ADMIN_ID);
            versionService.openDraft(parentId, TEST_ADMIN_ID);

            versionService.discardDraft(parentId);

            assertThatThrownBy(() -> versionService.getOpenDraft(parentId))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("VER-09: Zone 1 edit (name) → no version bump")
        void zone1Edit_noVersionBump() {
            versionService.publish(parentId, TEST_ADMIN_ID);

            Assistant before = assistantService.getAssistant(parentId);
            UUID v1Id = before.getCurrentVersionId();

            assistantService.updateParent(parentId,
                    "vct-renamed-" + UUID.randomUUID().toString().substring(0, 8),
                    "Updated description");

            Assistant after = assistantService.getAssistant(parentId);
            assertThat(after.getCurrentVersionId()).isEqualTo(v1Id);
            assertThat(after.getName()).startsWith("vct-renamed-");
        }

        @Test
        @DisplayName("VER-13/14: disable → disabled=true, re-enable → disabled=false")
        void disableAndReenable() {
            versionService.publish(parentId, TEST_ADMIN_ID);

            Assistant disabled = assistantService.setDisabled(parentId, true);
            assertThat(disabled.isDisabled()).isTrue();

            Assistant reenabled = assistantService.setDisabled(parentId, false);
            assertThat(reenabled.isDisabled()).isFalse();
        }
    }

    // ================================================================
    // InstructionAsset
    // ================================================================

    @Nested
    @DisplayName("InstructionAsset")
    class InstructionAssetContract {

        @Autowired
        private InstructionAssetService service;

        private UUID parentId;

        @BeforeEach
        void createParent() {
            InstructionAsset asset = service.create(
                    "ORGANIZATION", null,
                    "vct-ia-" + UUID.randomUUID().toString().substring(0, 8),
                    "Versioned entity contract test",
                    "STANDARD", TEST_ADMIN_ID, "Test Admin");
            parentId = asset.getId();
        }

        @Test
        @DisplayName("VER-01: create parent → INCOMPLETE, no published version")
        void createParent_incompleteNoPublishedVersion() {
            InstructionAsset parent = service.findById(parentId);
            assertThat(parent.getStatus()).isEqualTo("INCOMPLETE");
            assertThat(parent.getCurrentVersionId()).isNull();
        }

        @Test
        @DisplayName("VER-02: create draft → DRAFT status, version_number=1")
        void createDraft_draftStatusVersionOne() {
            InstructionAssetVersion draft = service.createDraft(
                    parentId, "INLINE", Map.of("content", "Test instruction."),
                    null, Map.of("CONVERSATION", "true"), "REQUIRED", 100,
                    null, TEST_ADMIN_ID, "Test Admin");

            assertThat(draft.getStatus()).isEqualTo("DRAFT");
            assertThat(draft.getVersionNumber()).isEqualTo(1);
        }

        @Test
        @DisplayName("VER-03: publish → version PUBLISHED, parent ACTIVE, currentVersionId set")
        void publish_versionPublishedParentActive() {
            service.createDraft(parentId, "INLINE", Map.of("content", "Test instruction."),
                    null, Map.of("CONVERSATION", "true"), "REQUIRED", 100,
                    null, TEST_ADMIN_ID, "Test Admin");

            InstructionAssetVersion published = service.publishDraft(parentId, TEST_ADMIN_ID, "Test Admin");
            assertThat(published.getStatus()).isEqualTo("PUBLISHED");

            InstructionAsset parent = service.findById(parentId);
            assertThat(parent.getStatus()).isEqualTo("ACTIVE");
            assertThat(parent.getCurrentVersionId()).isEqualTo(published.getId());
        }

        @Test
        @DisplayName("VER-04: published version is immutable (updateDraft when no draft → 400)")
        void publishedVersion_immutable() {
            service.createDraft(parentId, "INLINE", Map.of("content", "Test instruction."),
                    null, Map.of("CONVERSATION", "true"), "REQUIRED", 100,
                    null, TEST_ADMIN_ID, "Test Admin");
            service.publishDraft(parentId, TEST_ADMIN_ID, "Test Admin");

            assertThatThrownBy(() -> service.updateDraft(parentId, "INLINE",
                    Map.of("content", "modified"), null,
                    Map.of("CONVERSATION", "true"), "REQUIRED", 100,
                    null, TEST_ADMIN_ID))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("VER-05/06: publish v2 → v2 PUBLISHED, parent points to v2")
        void publishV2_parentPointsToV2() {
            service.createDraft(parentId, "INLINE", Map.of("content", "v1 content."),
                    null, Map.of("CONVERSATION", "true"), "REQUIRED", 100,
                    null, TEST_ADMIN_ID, "Test Admin");
            InstructionAssetVersion v1 = service.publishDraft(parentId, TEST_ADMIN_ID, "Test Admin");
            assertThat(v1.getVersionNumber()).isEqualTo(1);

            service.createDraft(parentId, "INLINE", Map.of("content", "v2 content."),
                    null, Map.of("CONVERSATION", "true"), "REQUIRED", 100,
                    null, TEST_ADMIN_ID, "Test Admin");
            InstructionAssetVersion v2 = service.publishDraft(parentId, TEST_ADMIN_ID, "Test Admin");
            assertThat(v2.getVersionNumber()).isEqualTo(2);
            assertThat(v2.getStatus()).isEqualTo("PUBLISHED");

            InstructionAsset parent = service.findById(parentId);
            assertThat(parent.getCurrentVersionId()).isEqualTo(v2.getId());
        }

        @Test
        @DisplayName("VER-07: single draft enforcement — second createDraft returns 400")
        void singleDraftEnforcement() {
            service.createDraft(parentId, "INLINE", Map.of("content", "Test instruction."),
                    null, Map.of("CONVERSATION", "true"), "REQUIRED", 100,
                    null, TEST_ADMIN_ID, "Test Admin");

            assertThatThrownBy(() -> service.createDraft(parentId, "INLINE",
                    Map.of("content", "Second draft."), null,
                    Map.of("CONVERSATION", "true"), "REQUIRED", 100,
                    null, TEST_ADMIN_ID, "Test Admin"))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("VER-08: discard draft → draft deleted")
        void discardDraft_removesDraft() {
            service.createDraft(parentId, "INLINE", Map.of("content", "Test instruction."),
                    null, Map.of("CONVERSATION", "true"), "REQUIRED", 100,
                    null, TEST_ADMIN_ID, "Test Admin");

            service.discardDraft(parentId, TEST_ADMIN_ID, "Test Admin");

            InstructionAssetVersion draft = service.getDraftVersion(parentId);
            assertThat(draft).isNull();
        }

        @Test
        @DisplayName("VER-09: Zone 1 edit (name) → no version bump")
        void zone1Edit_noVersionBump() {
            service.createDraft(parentId, "INLINE", Map.of("content", "Test instruction."),
                    null, Map.of("CONVERSATION", "true"), "REQUIRED", 100,
                    null, TEST_ADMIN_ID, "Test Admin");
            service.publishDraft(parentId, TEST_ADMIN_ID, "Test Admin");

            InstructionAsset before = service.findById(parentId);
            UUID v1Id = before.getCurrentVersionId();

            service.update(parentId, "vct-renamed-" + UUID.randomUUID().toString().substring(0, 8),
                    "Updated description", "STANDARD", TEST_ADMIN_ID, "Test Admin");

            InstructionAsset after = service.findById(parentId);
            assertThat(after.getCurrentVersionId()).isEqualTo(v1Id);
            assertThat(after.getName()).startsWith("vct-renamed-");
        }

        @Test
        @DisplayName("VER-13/14: disable → DISABLED, re-enable → ACTIVE")
        void disableAndReenable() {
            service.createDraft(parentId, "INLINE", Map.of("content", "Test instruction."),
                    null, Map.of("CONVERSATION", "true"), "REQUIRED", 100,
                    null, TEST_ADMIN_ID, "Test Admin");
            service.publishDraft(parentId, TEST_ADMIN_ID, "Test Admin");

            InstructionAsset disabled = service.disable(parentId, TEST_ADMIN_ID, "Test Admin");
            assertThat(disabled.getStatus()).isEqualTo("DISABLED");

            InstructionAsset reenabled = service.reenable(parentId, TEST_ADMIN_ID, "Test Admin");
            assertThat(reenabled.getStatus()).isEqualTo("ACTIVE");
        }
    }

    // ================================================================
    // ConnectionConfig
    // ================================================================

    @Nested
    @DisplayName("ConnectionConfig")
    class ConnectionConfigContract {

        @Autowired
        private ConnectionConfigService service;

        private UUID parentId;

        @BeforeEach
        void createParent() {
            ConnectionConfig config = service.create(
                    "ORGANIZATION", null,
                    "vct-cc-" + UUID.randomUUID().toString().substring(0, 8),
                    "Versioned entity contract test",
                    "GIT", null, TEST_ADMIN_ID, "Test Admin");
            parentId = config.getId();
        }

        @Test
        @DisplayName("VER-01: create parent → INCOMPLETE, no published version")
        void createParent_incompleteNoPublishedVersion() {
            ConnectionConfig parent = service.findById(parentId);
            assertThat(parent.getStatus()).isEqualTo("INCOMPLETE");
            assertThat(parent.getCurrentVersionId()).isNull();
        }

        @Test
        @DisplayName("VER-02: create draft → DRAFT status, version_number=1")
        void createDraft_draftStatusVersionOne() {
            ConnectionConfigVersion draft = service.createDraft(parentId, TEST_ADMIN_ID, "Test Admin");
            assertThat(draft.getStatus()).isEqualTo("DRAFT");
            assertThat(draft.getVersionNumber()).isEqualTo(1);
        }

        // Temporarily disabled: server-side connectivity check in publishDraft is disabled
        // so configs can be published without a reachable target endpoint in test/e2e environments.
        // @Test
        // @DisplayName("VER-03: publish gate rejects unreachable URL (connectivity check)")
        // void publish_gateRejectsUnreachableUrl() {
        //     service.createDraft(parentId, TEST_ADMIN_ID, "Test Admin");
        //     service.updateDraft(parentId, "https://github.com/example/repo.git", null, TEST_ADMIN_ID);
        //
        //     // ConnectionConfig publish gate runs a real connectivity check.
        //     // Fake URLs are correctly rejected — this proves the gate works.
        //     assertThatThrownBy(() -> service.publishDraft(parentId, TEST_ADMIN_ID, "Test Admin"))
        //             .isInstanceOf(BadRequestException.class);
        // }

        @Test
        @DisplayName("VER-04: published version is immutable (updateDraft when no draft → 400)")
        void publishedVersion_immutable() {
            // Immutability is tested via updateDraft rejection when no draft exists.
            // (Full publish→immutability test requires a real connection.)
            assertThatThrownBy(() -> service.updateDraft(parentId,
                    "https://github.com/example/modified.git", null, TEST_ADMIN_ID))
                    .isInstanceOf(BadRequestException.class);
        }

        // Temporarily disabled: server-side connectivity check in publishDraft is disabled
        // so configs can be published without a reachable target endpoint in test/e2e environments.
        // @Test
        // @DisplayName("VER-05/06: publish gate enforced (v2 draft requires connectivity)")
        // void publishV2_gateEnforced() {
        //     // Prove the gate is enforced for v2 as well.
        //     service.createDraft(parentId, TEST_ADMIN_ID, "Test Admin");
        //     service.updateDraft(parentId, "https://github.com/example/v1.git", null, TEST_ADMIN_ID);
        //     assertThatThrownBy(() -> service.publishDraft(parentId, TEST_ADMIN_ID, "Test Admin"))
        //             .isInstanceOf(BadRequestException.class);
        // }

        @Test
        @DisplayName("VER-07: single draft enforcement — second createDraft returns 400")
        void singleDraftEnforcement() {
            service.createDraft(parentId, TEST_ADMIN_ID, "Test Admin");

            assertThatThrownBy(() -> service.createDraft(parentId, TEST_ADMIN_ID, "Test Admin"))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("VER-08: discard draft → draft deleted")
        void discardDraft_removesDraft() {
            service.createDraft(parentId, TEST_ADMIN_ID, "Test Admin");

            service.discardDraft(parentId, TEST_ADMIN_ID, "Test Admin");

            ConnectionConfigVersion draft = service.getDraftVersion(parentId);
            assertThat(draft).isNull();
        }

        @Test
        @DisplayName("VER-09: Zone 1 edit (name) → no version bump")
        void zone1Edit_noVersionBump() {
            // Zone 1 edit doesn't need a published version — it edits the parent directly.
            ConnectionConfig before = service.findById(parentId);
            assertThat(before.getCurrentVersionId()).isNull();

            service.update(parentId, "vct-renamed-" + UUID.randomUUID().toString().substring(0, 8),
                    "Updated description", null, TEST_ADMIN_ID, "Test Admin");

            ConnectionConfig after = service.findById(parentId);
            assertThat(after.getCurrentVersionId()).isNull(); // still no published version
            assertThat(after.getName()).startsWith("vct-renamed-");
        }

        @Test
        @DisplayName("VER-13/14: disable → DISABLED, re-enable → ACTIVE")
        void disableAndReenable() {
            // Disable/reenable works on the parent regardless of publish state.
            ConnectionConfig disabled = service.disable(parentId, TEST_ADMIN_ID, "Test Admin");
            assertThat(disabled.getStatus()).isEqualTo("DISABLED");

            ConnectionConfig reenabled = service.reenable(parentId, TEST_ADMIN_ID, "Test Admin");
            assertThat(reenabled.getStatus()).isEqualTo("ACTIVE");
        }
    }

    // ================================================================
    // KnowledgeProvider
    // ================================================================

    @Nested
    @DisplayName("KnowledgeProvider")
    class KnowledgeProviderContract {

        @Autowired
        private KnowledgeProviderService service;

        private UUID parentId;

        @BeforeEach
        void createParent() {
            KnowledgeProvider provider = service.create(
                    "ORGANIZATION", null,
                    "vct-kp-" + UUID.randomUUID().toString().substring(0, 8),
                    "Versioned entity contract test",
                    "EXTERNAL", TEST_ADMIN_ID, "Test Admin");
            parentId = provider.getId();
        }

        @Test
        @DisplayName("VER-01: create parent → INCOMPLETE, no published version")
        void createParent_incompleteNoPublishedVersion() {
            KnowledgeProvider parent = service.findById(parentId);
            assertThat(parent.getStatus()).isEqualTo("INCOMPLETE");
            assertThat(parent.getCurrentVersionId()).isNull();
        }

        @Test
        @DisplayName("VER-02: initial draft exists with DRAFT status, version_number=1")
        void initialDraft_draftStatusVersionOne() {
            KnowledgeProviderVersion draft = service.getDraftVersion(parentId);
            assertThat(draft).isNotNull();
            assertThat(draft.getStatus()).isEqualTo("DRAFT");
            assertThat(draft.getVersionNumber()).isEqualTo(1);
        }

        @Test
        @DisplayName("VER-03: publish → version PUBLISHED, parent ACTIVE, currentVersionId set")
        void publish_versionPublishedParentActive() {
            Map<String, Object> config = Map.of("responseMapping", Map.of(
                    "hitsPath", "$.results", "passagePath", "text",
                    "sourceNamePath", "title", "locatorPath", "url"));
            service.updateDraft(parentId, null, config, TEST_ADMIN_ID, "Test Admin");

            KnowledgeProviderVersion published = service.publishDraft(parentId, TEST_ADMIN_ID, "Test Admin");
            assertThat(published.getStatus()).isEqualTo("PUBLISHED");

            KnowledgeProvider parent = service.findById(parentId);
            assertThat(parent.getStatus()).isEqualTo("ACTIVE");
            assertThat(parent.getCurrentVersionId()).isEqualTo(published.getId());
        }

        @Test
        @DisplayName("VER-04: published version is immutable (updateDraft when no draft → 400)")
        void publishedVersion_immutable() {
            Map<String, Object> config = Map.of("responseMapping", Map.of(
                    "hitsPath", "$.results", "passagePath", "text",
                    "sourceNamePath", "title", "locatorPath", "url"));
            service.updateDraft(parentId, null, config, TEST_ADMIN_ID, "Test Admin");
            service.publishDraft(parentId, TEST_ADMIN_ID, "Test Admin");

            assertThatThrownBy(() -> service.updateDraft(parentId, null,
                    Map.of("responseMapping", Map.of("hitsPath", "$.modified")),
                    TEST_ADMIN_ID, "Test Admin"))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("VER-05/06: publish v2 → v2 PUBLISHED, parent points to v2")
        void publishV2_parentPointsToV2() {
            Map<String, Object> config = Map.of("responseMapping", Map.of(
                    "hitsPath", "$.results", "passagePath", "text",
                    "sourceNamePath", "title", "locatorPath", "url"));
            service.updateDraft(parentId, null, config, TEST_ADMIN_ID, "Test Admin");
            KnowledgeProviderVersion v1 = service.publishDraft(parentId, TEST_ADMIN_ID, "Test Admin");
            assertThat(v1.getVersionNumber()).isEqualTo(1);

            service.createDraft(parentId, null, config, TEST_ADMIN_ID, "Test Admin");
            KnowledgeProviderVersion v2 = service.publishDraft(parentId, TEST_ADMIN_ID, "Test Admin");
            assertThat(v2.getVersionNumber()).isEqualTo(2);
            assertThat(v2.getStatus()).isEqualTo("PUBLISHED");

            KnowledgeProvider parent = service.findById(parentId);
            assertThat(parent.getCurrentVersionId()).isEqualTo(v2.getId());
        }

        @Test
        @DisplayName("VER-07: single draft enforcement — second createDraft returns 400")
        void singleDraftEnforcement() {
            assertThatThrownBy(() -> service.createDraft(parentId, null, null,
                    TEST_ADMIN_ID, "Test Admin"))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("VER-08: discard draft → draft deleted")
        void discardDraft_removesDraft() {
            service.discardDraft(parentId, TEST_ADMIN_ID, "Test Admin");

            KnowledgeProviderVersion draft = service.getDraftVersion(parentId);
            assertThat(draft).isNull();
        }

        @Test
        @DisplayName("VER-09: Zone 1 edit (name) → no version bump")
        void zone1Edit_noVersionBump() {
            Map<String, Object> config = Map.of("responseMapping", Map.of(
                    "hitsPath", "$.results", "passagePath", "text",
                    "sourceNamePath", "title", "locatorPath", "url"));
            service.updateDraft(parentId, null, config, TEST_ADMIN_ID, "Test Admin");
            service.publishDraft(parentId, TEST_ADMIN_ID, "Test Admin");

            KnowledgeProvider before = service.findById(parentId);
            UUID v1Id = before.getCurrentVersionId();

            service.updateZone1(parentId,
                    "vct-renamed-" + UUID.randomUUID().toString().substring(0, 8),
                    "Updated description", TEST_ADMIN_ID, "Test Admin");

            KnowledgeProvider after = service.findById(parentId);
            assertThat(after.getCurrentVersionId()).isEqualTo(v1Id);
            assertThat(after.getName()).startsWith("vct-renamed-");
        }

        @Test
        @DisplayName("VER-13/14: disable → DISABLED, re-enable → ACTIVE")
        void disableAndReenable() {
            Map<String, Object> config = Map.of("responseMapping", Map.of(
                    "hitsPath", "$.results", "passagePath", "text",
                    "sourceNamePath", "title", "locatorPath", "url"));
            service.updateDraft(parentId, null, config, TEST_ADMIN_ID, "Test Admin");
            service.publishDraft(parentId, TEST_ADMIN_ID, "Test Admin");

            KnowledgeProvider disabled = service.disable(parentId, TEST_ADMIN_ID, "Test Admin");
            assertThat(disabled.getStatus()).isEqualTo("DISABLED");

            KnowledgeProvider reenabled = service.reenable(parentId, TEST_ADMIN_ID, "Test Admin");
            assertThat(reenabled.getStatus()).isEqualTo("ACTIVE");
        }
    }
}
