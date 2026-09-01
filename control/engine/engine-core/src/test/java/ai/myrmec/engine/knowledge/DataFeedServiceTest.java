// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.governance.GovernanceProfileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for DataFeedService — verifies flat CRUD lifecycle
 * (create, triggerSync, disable).
 */
class DataFeedServiceTest extends IntegrationTestBase {

    @Autowired
    private DataFeedService service;

    @Autowired
    private KnowledgeProviderService providerService;

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

    private UUID createPublishedProviderVersion() {
        var provider = providerService.create(
                "ORGANIZATION", null, "feed-test-provider-" + System.nanoTime(), "Feed Test Provider",
                "MANAGED", TEST_ADMIN_ID, "Test Admin");
        // create() auto-creates an initial draft — use it directly.
        providerService.updateDraft(provider.getId(), null,
                Map.of("responseMapping", Map.of(
                        "hitsPath", "$.results", "passagePath", "text",
                        "sourceNamePath", "title", "locatorPath", "url")),
                TEST_ADMIN_ID, "Test Admin");
        return providerService.publishDraft(provider.getId(), TEST_ADMIN_ID, "Test Admin").getId();
    }

    @Test
    void create_persistsWithActiveStatus() {
        var versionId = createPublishedProviderVersion();
        var feed = service.create(
                "ORGANIZATION", null, "test-feed", "Test Feed",
                versionId, "product-docs", null, Map.of(), "0 2 * * *",
                TEST_ADMIN_ID, "Test Admin");

        assertThat(feed.getId()).isNotNull();
        assertThat(feed.getStatus()).isEqualTo("ACTIVE");
        assertThat(feed.getName()).isEqualTo("test-feed");
        assertThat(feed.getDatasetName()).isEqualTo("product-docs");
        assertThat(feed.getSyncSchedule()).isEqualTo("0 2 * * *");
        assertThat(feed.getSyncStatus()).isEqualTo("NEVER");
    }

    @Test
    void triggerSync_updatesSyncStatus() {
        var versionId = createPublishedProviderVersion();
        var feed = service.create(
                "ORGANIZATION", null, "test-sync-feed", "Test Sync Feed",
                versionId, "docs", null, Map.of(), null,
                TEST_ADMIN_ID, "Test Admin");

        var synced = service.triggerSync(feed.getId(), TEST_ADMIN_ID, "Test Admin");
        assertThat(synced.getSyncStatus()).isIn("SYNCING", "COMPLETED", "FAILED");
    }

    @Test
    void disable_changesStatusToDisabled() {
        var versionId = createPublishedProviderVersion();
        var feed = service.create(
                "ORGANIZATION", null, "test-disable-feed", "Test Disable Feed",
                versionId, "docs", null, Map.of(), null,
                TEST_ADMIN_ID, "Test Admin");

        var disabled = service.disable(feed.getId(), TEST_ADMIN_ID, "Test Admin");
        assertThat(disabled.getStatus()).isEqualTo("DISABLED");
    }
}