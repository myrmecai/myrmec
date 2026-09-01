// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.connection;

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
 * Integration tests for ConnectionConfigService — verifies the full
 * versioned entity lifecycle (create, draft, publish, disable, reenable, archive).
 */
class ConnectionConfigServiceTest extends IntegrationTestBase {

    @Autowired
    private ConnectionConfigService service;

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
        var config = service.create(
                "ORGANIZATION", null, "test-git-conn", "Test Git Connection",
                "GIT", null, TEST_ADMIN_ID, "Test Admin");

        assertThat(config.getId()).isNotNull();
        assertThat(config.getStatus()).isEqualTo("INCOMPLETE");
        assertThat(config.getType()).isEqualTo("GIT");
        assertThat(config.getName()).isEqualTo("test-git-conn");
    }

    // Temporarily disabled: server-side connectivity check in publishDraft is disabled
    // so configs can be published without a reachable target endpoint in test/e2e environments.
    // @Test
    // void createDraft_andPublish_gateRejectsUnreachableUrl() {
    //     var config = service.create(
    //             "ORGANIZATION", null, "test-http-conn", "Test HTTP Connection",
    //             "HTTP", null, TEST_ADMIN_ID, "Test Admin");
    //
    //     var draft = service.createDraft(config.getId(), TEST_ADMIN_ID, "Test Admin");
    //     assertThat(draft.getStatus()).isEqualTo("DRAFT");
    //     assertThat(draft.getVersionNumber()).isEqualTo(1);
    //
    //     // Update draft with URL and testEndpoint (required for HTTP connections).
    //     service.updateDraft(config.getId(), "https://api.example.com",
    //             Map.of("testEndpoint", "/health"), TEST_ADMIN_ID);
    //
    //     // Publish gate runs a real connectivity check — fake URLs are correctly rejected.
    //     // This proves the gate works. A real connectivity test needs a real endpoint.
    //     assertThatThrownBy(() -> service.publishDraft(config.getId(), TEST_ADMIN_ID, "Test Admin"))
    //             .isInstanceOf(ai.myrmec.engine._system.exception.BadRequestException.class);
    // }

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