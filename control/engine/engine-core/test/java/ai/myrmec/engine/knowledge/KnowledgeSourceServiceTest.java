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
 * Integration tests for KnowledgeSourceService — verifies flat CRUD
 * lifecycle (create, disable, archive).
 */
class KnowledgeSourceServiceTest extends IntegrationTestBase {

    @Autowired
    private KnowledgeSourceService service;

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
                "ORGANIZATION", null, "source-test-provider-" + System.nanoTime(), "Source Test Provider",
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
        var source = service.create(
                "ORGANIZATION", null, "test-source", "Test Source",
                versionId, Map.of(), "REQUIRED", 100,
                TEST_ADMIN_ID, "Test Admin");

        assertThat(source.getId()).isNotNull();
        assertThat(source.getStatus()).isEqualTo("ACTIVE");
        assertThat(source.getName()).isEqualTo("test-source");
        assertThat(source.getAvailability()).isEqualTo("REQUIRED");
        assertThat(source.getPriority()).isEqualTo(100);
    }

    @Test
    void disable_changesStatusToDisabled() {
        var versionId = createPublishedProviderVersion();
        var source = service.create(
                "ORGANIZATION", null, "test-disable-source", "Test Disable Source",
                versionId, Map.of(), "OPTIONAL", 200,
                TEST_ADMIN_ID, "Test Admin");

        var disabled = service.disable(source.getId(), TEST_ADMIN_ID, "Test Admin");
        assertThat(disabled.getStatus()).isEqualTo("DISABLED");
    }

    @Test
    void archive_changesStatusToArchived() {
        var versionId = createPublishedProviderVersion();
        var source = service.create(
                "ORGANIZATION", null, "test-archive-source", "Test Archive Source",
                versionId, Map.of(), "REQUIRED", 50,
                TEST_ADMIN_ID, "Test Admin");

        var archived = service.archive(source.getId(), TEST_ADMIN_ID, "Test Admin");
        assertThat(archived.getStatus()).isEqualTo("ARCHIVED");
    }
}