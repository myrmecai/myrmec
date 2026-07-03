// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

import ai.myrmec.engine.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for KnowledgeProviderService — verifies the full
 * versioned entity lifecycle (create, draft, publish, disable, reenable).
 */
class KnowledgeProviderServiceTest extends IntegrationTestBase {

    @Autowired
    private KnowledgeProviderService service;

    @Test
    void create_persistsWithIncompleteStatus() {
        var name = "test-managed-provider-" + System.nanoTime();
        var provider = service.create(
                "ORGANIZATION", null, name, "Test Managed Provider",
                "MANAGED", TEST_ADMIN_ID, "Test Admin");

        assertThat(provider.getId()).isNotNull();
        assertThat(provider.getStatus()).isEqualTo("INCOMPLETE");
        assertThat(provider.getType()).isEqualTo("MANAGED");
        assertThat(provider.getName()).isEqualTo(name);
    }

    @Test
    void createDuplicateName_throwsBadRequest() {
        String dupName = "dup-provider-" + System.nanoTime();
        service.create(
                "ORGANIZATION", null, dupName, "First Provider",
                "MANAGED", TEST_ADMIN_ID, "Test Admin");

        assertThatThrownBy(() -> service.create(
                "ORGANIZATION", null, dupName, "Second Provider",
                "MANAGED", TEST_ADMIN_ID, "Test Admin"))
                .isInstanceOf(ai.myrmec.engine._system.exception.BadRequestException.class);
    }

    @Test
    void createDraft_andPublish_changesStatusToActive() {
        var provider = service.create(
                "ORGANIZATION", null, "test-external-provider-" + System.nanoTime(), "Test External Provider",
                "EXTERNAL", TEST_ADMIN_ID, "Test Admin");

        var draft = service.createDraft(provider.getId(), null, null, TEST_ADMIN_ID, "Test Admin");
        assertThat(draft.getStatus()).isEqualTo("DRAFT");
        assertThat(draft.getVersionNumber()).isEqualTo(1);

        // Publish
        var published = service.publishDraft(provider.getId(), TEST_ADMIN_ID, "Test Admin");
        assertThat(published.getStatus()).isEqualTo("PUBLISHED");

        // Verify parent status updated
        var updated = service.findById(provider.getId());
        assertThat(updated.getStatus()).isEqualTo("ACTIVE");
        assertThat(updated.getCurrentVersionId()).isEqualTo(published.getId());
    }

    @Test
    void discardDraft_removesDraft() {
        var provider = service.create(
                "ORGANIZATION", null, "test-discard-provider-" + System.nanoTime(), "Test Discard Provider",
                "MANAGED", TEST_ADMIN_ID, "Test Admin");

        service.createDraft(provider.getId(), null, null, TEST_ADMIN_ID, "Test Admin");
        assertThat(service.getDraftVersion(provider.getId())).isNotNull();

        service.discardDraft(provider.getId(), TEST_ADMIN_ID, "Test Admin");
        assertThat(service.getDraftVersion(provider.getId())).isNull();
    }

    @Test
    void disableAndReenable_changesStatus() {
        var provider = service.create(
                "ORGANIZATION", null, "test-disable-provider-" + System.nanoTime(), "Test Disable Provider",
                "MANAGED", TEST_ADMIN_ID, "Test Admin");

        var disabled = service.disable(provider.getId(), TEST_ADMIN_ID, "Test Admin");
        assertThat(disabled.getStatus()).isEqualTo("DISABLED");

        var reenabled = service.reenable(provider.getId(), TEST_ADMIN_ID, "Test Admin");
        assertThat(reenabled.getStatus()).isEqualTo("ACTIVE");
    }
}