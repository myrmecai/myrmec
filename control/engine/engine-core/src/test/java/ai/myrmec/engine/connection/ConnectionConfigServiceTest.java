// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.connection;

import ai.myrmec.engine.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for ConnectionConfigService — verifies the full
 * versioned entity lifecycle (create, draft, publish, disable, reenable, archive).
 */
class ConnectionConfigServiceTest extends IntegrationTestBase {

    @Autowired
    private ConnectionConfigService service;

    @Test
    void create_persistsWithIncompleteStatus() {
        var config = service.create(
                "ORGANIZATION", null, "test-git-conn", "Test Git Connection",
                "GIT", null, TEST_ADMIN_ID, "Test Admin");

        assertThat(config.getId()).isNotNull();
        assertThat(config.getStatus()).isEqualTo("INCOMPLETE");
        assertThat(config.getType()).isEqualTo("GIT");
        assertThat(config.getName()).isEqualTo("test-git-conn");
    }

    @Test
    void createDraft_andPublish_changesStatusToActive() {
        var config = service.create(
                "ORGANIZATION", null, "test-http-conn", "Test HTTP Connection",
                "HTTP", null, TEST_ADMIN_ID, "Test Admin");

        var draft = service.createDraft(config.getId(), TEST_ADMIN_ID, "Test Admin");
        assertThat(draft.getStatus()).isEqualTo("DRAFT");
        assertThat(draft.getVersionNumber()).isEqualTo(1);

        // Update draft with URL
        service.updateDraft(config.getId(), "https://api.example.com", null, TEST_ADMIN_ID);

        // Publish
        var published = service.publishDraft(config.getId(), TEST_ADMIN_ID, "Test Admin");
        assertThat(published.getStatus()).isEqualTo("PUBLISHED");

        // Verify parent status updated
        var updated = service.findById(config.getId());
        assertThat(updated.getStatus()).isEqualTo("ACTIVE");
        assertThat(updated.getCurrentVersionId()).isEqualTo(published.getId());
    }

    @Test
    void publishWithoutUrl_throwsBadRequest() {
        var config = service.create(
                "ORGANIZATION", null, "test-s3-conn", "Test S3 Connection",
                "S3", null, TEST_ADMIN_ID, "Test Admin");

        service.createDraft(config.getId(), TEST_ADMIN_ID, "Test Admin");

        assertThatThrownBy(() -> service.publishDraft(config.getId(), TEST_ADMIN_ID, "Test Admin"))
                .isInstanceOf(ai.myrmec.engine._system.exception.BadRequestException.class);
    }

    @Test
    void disableAndReenable_changesStatus() {
        var config = service.create(
                "ORGANIZATION", null, "test-db-conn", "Test DB Connection",
                "DB_SCHEMA", null, TEST_ADMIN_ID, "Test Admin");

        var disabled = service.disable(config.getId(), TEST_ADMIN_ID, "Test Admin");
        assertThat(disabled.getStatus()).isEqualTo("DISABLED");

        var reenabled = service.reenable(config.getId(), TEST_ADMIN_ID, "Test Admin");
        assertThat(reenabled.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void duplicateName_throwsBadRequest() {
        service.create(
                "ORGANIZATION", null, "dup-conn", "First", "GIT",
                null, TEST_ADMIN_ID, "Test Admin");

        assertThatThrownBy(() -> service.create(
                "ORGANIZATION", null, "dup-conn", "Second", "GIT",
                null, TEST_ADMIN_ID, "Test Admin"))
                .isInstanceOf(ai.myrmec.engine._system.exception.BadRequestException.class);
    }
}