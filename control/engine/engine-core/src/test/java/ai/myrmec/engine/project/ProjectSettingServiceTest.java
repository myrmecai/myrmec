// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.project;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.project.dto.CreateProjectRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for ProjectSettingService — verifies the key-value
 * store with typed accessors (getString, getInt, getBoolean).
 */
class ProjectSettingServiceTest extends IntegrationTestBase {

    @Autowired
    private ProjectSettingService service;

    @Autowired
    private ProjectService projectService;

    private UUID createTestProject() {
        var request = new CreateProjectRequest();
        request.setName("setting-test-" + System.nanoTime());
        return projectService.create(request).getId();
    }

    @Test
    void update_andGetString_works() {
        var projectId = createTestProject();
        service.update(projectId, "my.string", "hello world", TEST_ADMIN_ID);

        var value = service.getString(projectId, "my.string", "default");
        assertThat(value).isEqualTo("hello world");

        // Default when key doesn't exist
        var missing = service.getString(projectId, "nonexistent", "default");
        assertThat(missing).isEqualTo("default");
    }

    @Test
    void update_andGetInt_works() {
        var projectId = createTestProject();
        service.update(projectId, "my.int", "42", TEST_ADMIN_ID);

        var value = service.getInt(projectId, "my.int", 0);
        assertThat(value).isEqualTo(42);

        // Default when key doesn't exist
        var missing = service.getInt(projectId, "nonexistent", 99);
        assertThat(missing).isEqualTo(99);
    }

    @Test
    void update_andGetBoolean_works() {
        var projectId = createTestProject();
        service.update(projectId, "my.bool", "true", TEST_ADMIN_ID);

        var value = service.getBoolean(projectId, "my.bool", false);
        assertThat(value).isTrue();

        // Default when key doesn't exist
        var missing = service.getBoolean(projectId, "nonexistent", true);
        assertThat(missing).isTrue();
    }

    @Test
    void update_overwritesExistingValue() {
        var projectId = createTestProject();
        service.update(projectId, "my.value", "first", TEST_ADMIN_ID);
        service.update(projectId, "my.value", "second", TEST_ADMIN_ID);

        var value = service.getString(projectId, "my.value", "default");
        assertThat(value).isEqualTo("second");
    }

    @Test
    void findAll_returnsAllSettingsForProject() {
        var projectId = createTestProject();
        service.update(projectId, "key1", "val1", TEST_ADMIN_ID);
        service.update(projectId, "key2", "val2", TEST_ADMIN_ID);

        var settings = service.findAll(projectId);
        assertThat(settings).hasSize(2);
        assertThat(settings).extracting(ProjectSetting::getSettingKey)
                .containsExactlyInAnyOrder("key1", "key2");
    }
}