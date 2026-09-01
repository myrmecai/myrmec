// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.audit;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.project.ProjectSetting;
import ai.myrmec.engine.project.ProjectSettingRepository;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit chain gating tests — verify the two-level gating model.
 *
 * <p>STRICT profile forces chaining ON (project setting ignored).
 * STANDARD/FLEXIBLE: chaining only if project setting
 * {@code audit_integrity_enabled} is true (default off).
 */
@Tag("INV-6")
@Tag("SG9")
@DisplayName("Audit Chain Gating")
class AuditChainGatingTest extends IntegrationTestBase {

    @Autowired
    private AuditEventService auditEventService;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private ProjectSettingRepository projectSettingRepository;

    @Autowired
    private TestDataBuilder data;

    private static final String SCOPE_PROJECT = "PROJECT";

    @Test
    @DisplayName("STANDARD profile: chaining OFF by default (no project setting)")
    void chainingOffByDefault() {
        UUID projectId = data.project().create().getId();

        writeProjectEvent(projectId, "CREATED", "entity-1");

        // Event should NOT have a hash (chaining is off by default)
        var events = auditEventRepository.findByScopeTypeAndProjectIdOrderByIdAsc(SCOPE_PROJECT, projectId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventHash()).isNull();
    }

    @Test
    @DisplayName("STANDARD profile: chaining ON when project setting is true")
    void chainingOnWhenSettingTrue() {
        UUID projectId = data.project().create().getId();

        enableChaining(projectId);
        writeProjectEvent(projectId, "CREATED", "entity-1");

        var events = auditEventRepository.findByScopeTypeAndProjectIdOrderByIdAsc(SCOPE_PROJECT, projectId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventHash()).isNotNull();
        assertThat(events.get(0).getPrevEventHash()).isNotNull();
    }

    @Test
    @DisplayName("STANDARD profile: chaining OFF when project setting is false")
    void chainingOffWhenSettingFalse() {
        UUID projectId = data.project().create().getId();

        // Explicitly set to false
        ProjectSetting setting = new ProjectSetting();
        setting.setProjectId(projectId);
        setting.setSettingKey("audit_integrity_enabled");
        setting.setValueType("STRING");
        setting.setSettingValue("false");
        projectSettingRepository.save(setting);

        writeProjectEvent(projectId, "CREATED", "entity-1");

        var events = auditEventRepository.findByScopeTypeAndProjectIdOrderByIdAsc(SCOPE_PROJECT, projectId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventHash()).isNull();
    }

    @Test
    @DisplayName("ORG-scope events: not chained under STANDARD profile")
    void orgScopeNotChainedUnderStandard() {
        // The default profile is STANDARD (from test config), ORG-scope
        // events should not be chained
        auditEventService.recordEvent(
                "test_entity", UUID.randomUUID(), "CREATED",
                "ORGANIZATION", null,
                null, "Gating Test", null, null,
                null, null, null);

        // The ORG event should not have a hash
        var events = auditEventRepository.findByScopeTypeAndProjectIdOrderByIdAsc("ORGANIZATION", null);
        // There may be other ORG events from test setup; just check the latest
        if (!events.isEmpty()) {
            // The last event should be ours — but it might not be the very last
            // if other test setup ran after. Just verify it doesn't have a hash.
            // (Under STANDARD, ORG events are not chained.)
            boolean anyChainedOrgEvent = events.stream()
                    .anyMatch(e -> e.getEventHash() != null && "test_entity".equals(e.getEntityType()));
            assertThat(anyChainedOrgEvent).isFalse();
        }
    }

    // --- helpers ---

    private void enableChaining(UUID projectId) {
        ProjectSetting setting = new ProjectSetting();
        setting.setProjectId(projectId);
        setting.setSettingKey("audit_integrity_enabled");
        setting.setValueType("STRING");
        setting.setSettingValue("true");
        projectSettingRepository.save(setting);
    }

    private void writeProjectEvent(UUID projectId, String eventType, String entityName) {
        auditEventService.recordEvent(
                "test_entity", UUID.randomUUID(), eventType,
                SCOPE_PROJECT, projectId,
                null, "Gating Test", null, null,
                null, Map.of("name", entityName), null);
    }
}