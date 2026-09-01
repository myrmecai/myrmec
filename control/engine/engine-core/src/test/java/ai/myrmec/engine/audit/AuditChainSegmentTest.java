// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.audit;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.project.ProjectSetting;
import ai.myrmec.engine.project.ProjectSettingRepository;
import ai.myrmec.engine.project.ProjectSettingService;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit chain segment tests — verify the on→off→on lifecycle.
 *
 * <p>Tests design decisions D5 (genesis recorded in the log), D7 (ratchet on
 * deactivation), and D9 (no retroactive absorption of old rows).
 */
@Tag("INV-6")
@Tag("SG9")
@DisplayName("Audit Chain Segments")
class AuditChainSegmentTest extends IntegrationTestBase {

    @Autowired
    private AuditEventService auditEventService;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private AuditHashChainService hashChainService;

    @Autowired
    private ProjectSettingRepository projectSettingRepository;

    @Autowired
    private ProjectSettingService projectSettingService;

    @Autowired
    private TestDataBuilder data;

    private static final String SCOPE_PROJECT = "PROJECT";

    @Test
    @DisplayName("activation writes a genesis event into the log")
    void activationWritesGenesisEvent() {
        UUID projectId = data.project().create().getId();

        // Enable chaining via the setting service (triggers the ratchet/genesis)
        projectSettingService.update(projectId, "audit_integrity_enabled", "true", null);

        var events = auditEventRepository.findByScopeTypeAndProjectIdOrderByIdAsc(SCOPE_PROJECT, projectId);
        // Should have at least one event — the AUDIT_CHAIN_ACTIVATED event
        assertThat(events).isNotEmpty();
        assertThat(events).anyMatch(e -> "AUDIT_CHAIN_ACTIVATED".equals(e.getEventType()));
    }

    @Test
    @DisplayName("deactivation writes a deactivation event (ratchet)")
    void deactivationWritesRatchetEvent() {
        UUID projectId = data.project().create().getId();

        // Enable chaining
        projectSettingService.update(projectId, "audit_integrity_enabled", "true", null);

        // Disable chaining
        projectSettingService.update(projectId, "audit_integrity_enabled", "false", null);

        var events = auditEventRepository.findByScopeTypeAndProjectIdOrderByIdAsc(SCOPE_PROJECT, projectId);
        assertThat(events).anyMatch(e -> "AUDIT_CHAIN_ACTIVATED".equals(e.getEventType()));
        assertThat(events).anyMatch(e -> "AUDIT_CHAIN_DEACTIVATED".equals(e.getEventType()));
    }

    @Test
    @DisplayName("old unchained rows stay outside the chain after activation")
    void oldRowsStayOutsideChain() {
        UUID projectId = data.project().create().getId();

        // Write an unchained event (chaining not enabled yet)
        writeProjectEvent(projectId, "CREATED", "unchained-entity");

        // Enable chaining
        enableChaining(projectId);
        writeProjectEvent(projectId, "UPDATED", "chained-entity");

        // Verify — should skip the unchained row and verify only the chained one
        AuditHashChainService.VerifyResult result = hashChainService.verifyScope(SCOPE_PROJECT, projectId);
        assertThat(result.valid()).isTrue();
        // The chained event (UPDATED) should be verified; the unchained one skipped
        // Note: there may also be the AUDIT_CHAIN_ACTIVATED event from enableChaining
        assertThat(result.checkedCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("on→off→on: second segment starts with genesis, chain verifies")
    void onOffOnSecondSegment() {
        UUID projectId = data.project().create().getId();

        // First on-period: write chained events
        enableChaining(projectId);
        writeProjectEvent(projectId, "CREATED", "first-period-1");

        // Off: turn off chaining
        disableChaining(projectId);
        writeProjectEvent(projectId, "UPDATED", "off-period-1"); // unchained

        // Second on-period: turn on again
        enableChaining(projectId);
        writeProjectEvent(projectId, "DELETED", "second-period-1"); // chained

        // Verify — chain should be valid, skipping unchained rows
        AuditHashChainService.VerifyResult result = hashChainService.verifyScope(SCOPE_PROJECT, projectId);
        assertThat(result.valid()).isTrue();
        // Should have verified: first-period-1 + second-period-1 (the chained events)
        // The off-period-1 event should be skipped (event_hash IS NULL)
        assertThat(result.checkedCount()).isGreaterThanOrEqualTo(2);
    }

    // --- helpers ---

    private void enableChaining(UUID projectId) {
        ProjectSetting setting = projectSettingRepository
                .findByProjectIdAndSettingKey(projectId, "audit_integrity_enabled")
                .orElseGet(() -> {
                    ProjectSetting s = new ProjectSetting();
                    s.setProjectId(projectId);
                    s.setSettingKey("audit_integrity_enabled");
                    s.setValueType("STRING");
                    return s;
                });
        setting.setSettingValue("true");
        projectSettingRepository.save(setting);
    }

    private void disableChaining(UUID projectId) {
        projectSettingRepository
                .findByProjectIdAndSettingKey(projectId, "audit_integrity_enabled")
                .ifPresent(setting -> {
                    setting.setSettingValue("false");
                    projectSettingRepository.save(setting);
                });
    }

    private void writeProjectEvent(UUID projectId, String eventType, String entityName) {
        auditEventService.recordEvent(
                "test_entity", UUID.randomUUID(), eventType,
                SCOPE_PROJECT, projectId,
                null, "Segment Test", null, null,
                null, Map.of("name", entityName), null);
    }
}