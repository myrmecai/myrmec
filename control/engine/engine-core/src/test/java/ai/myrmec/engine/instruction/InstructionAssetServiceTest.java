// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.instruction;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.governance.GovernanceProfileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for InstructionAssetService — verifies the full
 * versioned entity lifecycle (create, draft, publish, disable, reenable, archive).
 */
class InstructionAssetServiceTest extends IntegrationTestBase {

    @Autowired
    private InstructionAssetService service;

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

    @Test
    void create_persistsWithIncompleteStatus() {
        var asset = service.create(
                "ORGANIZATION", null, "test-instruction", "Test instruction",
                "STANDARD", TEST_ADMIN_ID, "Test Admin");

        assertThat(asset.getId()).isNotNull();
        assertThat(asset.getStatus()).isEqualTo("INCOMPLETE");
        assertThat(asset.getCategory()).isEqualTo("STANDARD");
    }

    @Test
    void createDraft_andPublish_changesStatusToActive() {
        var asset = service.create(
                "ORGANIZATION", null, "test-inline-asset", "Test inline asset",
                "REQUIREMENT", TEST_ADMIN_ID, "Test Admin");

        var draft = service.createDraft(
                asset.getId(),
                "INLINE",
                Map.of("content", "This is a test instruction."),
                null,
                Map.of("CONVERSATION", "true"),
                "REQUIRED",
                100,
                null,
                TEST_ADMIN_ID,
                "Test Admin");

        assertThat(draft.getStatus()).isEqualTo("DRAFT");
        assertThat(draft.getSourceType()).isEqualTo("INLINE");

        var published = service.publishDraft(asset.getId(), TEST_ADMIN_ID, "Test Admin");
        assertThat(published.getStatus()).isEqualTo("PUBLISHED");

        var updated = service.findById(asset.getId());
        assertThat(updated.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void duplicateName_throwsBadRequest() {
        service.create(
                "ORGANIZATION", null, "dup-asset", "First", "STANDARD",
                TEST_ADMIN_ID, "Test Admin");

        assertThatThrownBy(() -> service.create(
                "ORGANIZATION", null, "dup-asset", "Second", "STANDARD",
                TEST_ADMIN_ID, "Test Admin"))
                .isInstanceOf(ai.myrmec.engine._system.exception.BadRequestException.class);
    }

    @Test
    void disableAndReenable_changesStatus() {
        var asset = service.create(
                "ORGANIZATION", null, "disable-test", "Test", "PERSONA",
                TEST_ADMIN_ID, "Test Admin");

        var disabled = service.disable(asset.getId(), TEST_ADMIN_ID, "Test Admin");
        assertThat(disabled.getStatus()).isEqualTo("DISABLED");

        var reenabled = service.reenable(asset.getId(), TEST_ADMIN_ID, "Test Admin");
        assertThat(reenabled.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void archive_changesStatus() {
        var asset = service.create(
                "ORGANIZATION", null, "archive-test", "Test", "GOAL",
                TEST_ADMIN_ID, "Test Admin");

        var archived = service.archive(asset.getId(), TEST_ADMIN_ID, "Test Admin");
        assertThat(archived.getStatus()).isEqualTo("ARCHIVED");
    }
}