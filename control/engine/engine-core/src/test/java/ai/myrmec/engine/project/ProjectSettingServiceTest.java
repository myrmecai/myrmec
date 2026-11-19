// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.team;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.team.dto.CreateTeamRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for TeamSettingService — verifies the key-value
 * store with typed accessors (getString, getInt, getBoolean).
 */
class ProjectSettingServiceTest extends IntegrationTestBase {

    @Autowired
    private TeamSettingService service;

    @Autowired
    private TeamService TeamService;

    private UUID createTestProject() {
        var request = new CreateTeamRequest();
        request.setName("setting-test-" + System.nanoTime());
        return TeamService.create(request).getId();
    }

    @Test
    void update_andGetString_works() {
        var teamId = createTestProject();
        service.update(teamId, "my.string", "hello world", TEST_ADMIN_ID);

        var value = service.getString(teamId, "my.string", "default");
        assertThat(value).isEqualTo("hello world");

        // Default when key doesn't exist
        var missing = service.getString(teamId, "nonexistent", "default");
        assertThat(missing).isEqualTo("default");
    }

    @Test
    void update_andGetInt_works() {
        var teamId = createTestProject();
        service.update(teamId, "my.int", "42", TEST_ADMIN_ID);

        var value = service.getInt(teamId, "my.int", 0);
        assertThat(value).isEqualTo(42);

        // Default when key doesn't exist
        var missing = service.getInt(teamId, "nonexistent", 99);
        assertThat(missing).isEqualTo(99);
    }

    @Test
    void update_andGetBoolean_works() {
        var teamId = createTestProject();
        service.update(teamId, "my.bool", "true", TEST_ADMIN_ID);

        var value = service.getBoolean(teamId, "my.bool", false);
        assertThat(value).isTrue();

        // Default when key doesn't exist
        var missing = service.getBoolean(teamId, "nonexistent", true);
        assertThat(missing).isTrue();
    }

    @Test
    void update_overwritesExistingValue() {
        var teamId = createTestProject();
        service.update(teamId, "my.value", "first", TEST_ADMIN_ID);
        service.update(teamId, "my.value", "second", TEST_ADMIN_ID);

        var value = service.getString(teamId, "my.value", "default");
        assertThat(value).isEqualTo("second");
    }

    @Test
    void findAll_returnsAllSettingsForProject() {
        var teamId = createTestProject();
        service.update(teamId, "key1", "val1", TEST_ADMIN_ID);
        service.update(teamId, "key2", "val2", TEST_ADMIN_ID);

        var settings = service.findAll(teamId);
        assertThat(settings).hasSize(2);
        assertThat(settings).extracting(TeamSetting::getSettingKey)
                .containsExactlyInAnyOrder("key1", "key2");
    }
}