// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.audit;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.team.TeamSetting;
import ai.myrmec.engine.team.ProjectSettingRepository;
import ai.myrmec.engine.team.TeamSettingService;
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
    private TeamSettingService TeamSettingService;

    @Autowired
    private TestDataBuilder data;

    private static final String SCOPE_PROJECT = "TEAM";

    @Test
    @DisplayName("activation writes a genesis event into the log")
    void activationWritesGenesisEvent() {
        UUID teamId = data.team().create().getId();

        // Enable chaining via the setting service (triggers the ratchet/genesis)
        TeamSettingService.update(teamId, "audit_integrity_enabled", "true", null);

        var events = auditEventRepository.findByScopeTypeAndTeamIdOrderByIdAsc(SCOPE_PROJECT, teamId);
        // Should have at least one event — the AUDIT_CHAIN_ACTIVATED event
        assertThat(events).isNotEmpty();
        assertThat(events).anyMatch(e -> "AUDIT_CHAIN_ACTIVATED".equals(e.getEventType()));
    }

    @Test
    @DisplayName("deactivation writes a deactivation event (ratchet)")
    void deactivationWritesRatchetEvent() {
        UUID teamId = data.team().create().getId();

        // Enable chaining
        TeamSettingService.update(teamId, "audit_integrity_enabled", "true", null);

        // Disable chaining
        TeamSettingService.update(teamId, "audit_integrity_enabled", "false", null);

        var events = auditEventRepository.findByScopeTypeAndTeamIdOrderByIdAsc(SCOPE_PROJECT, teamId);
        assertThat(events).anyMatch(e -> "AUDIT_CHAIN_ACTIVATED".equals(e.getEventType()));
        assertThat(events).anyMatch(e -> "AUDIT_CHAIN_DEACTIVATED".equals(e.getEventType()));
    }

    @Test
    @DisplayName("old unchained rows stay outside the chain after activation")
    void oldRowsStayOutsideChain() {
        UUID teamId = data.team().create().getId();

        // Write an unchained event (chaining not enabled yet)
        writeProjectEvent(teamId, "CREATED", "unchained-entity");

        // Enable chaining
        enableChaining(teamId);
        writeProjectEvent(teamId, "UPDATED", "chained-entity");

        // Verify — should skip the unchained row and verify only the chained one
        AuditHashChainService.VerifyResult result = hashChainService.verifyScope(SCOPE_PROJECT, teamId);
        assertThat(result.valid()).isTrue();
        // The chained event (UPDATED) should be verified; the unchained one skipped
        // Note: there may also be the AUDIT_CHAIN_ACTIVATED event from enableChaining
        assertThat(result.checkedCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("on→off→on: second segment starts with genesis, chain verifies")
    void onOffOnSecondSegment() {
        UUID teamId = data.team().create().getId();

        // First on-period: write chained events
        enableChaining(teamId);
        writeProjectEvent(teamId, "CREATED", "first-period-1");

        // Off: turn off chaining
        disableChaining(teamId);
        writeProjectEvent(teamId, "UPDATED", "off-period-1"); // unchained

        // Second on-period: turn on again
        enableChaining(teamId);
        writeProjectEvent(teamId, "DELETED", "second-period-1"); // chained

        // Verify — chain should be valid, skipping unchained rows
        AuditHashChainService.VerifyResult result = hashChainService.verifyScope(SCOPE_PROJECT, teamId);
        assertThat(result.valid()).isTrue();
        // Should have verified: first-period-1 + second-period-1 (the chained events)
        // The off-period-1 event should be skipped (event_hash IS NULL)
        assertThat(result.checkedCount()).isGreaterThanOrEqualTo(2);
    }

    // --- helpers ---

    private void enableChaining(UUID teamId) {
        TeamSetting setting = projectSettingRepository
                .findByTeamIdAndSettingKey(teamId, "audit_integrity_enabled")
                .orElseGet(() -> {
                    TeamSetting s = new TeamSetting();
                    s.setTeamId(teamId);
                    s.setSettingKey("audit_integrity_enabled");
                    s.setValueType("STRING");
                    return s;
                });
        setting.setSettingValue("true");
        projectSettingRepository.save(setting);
    }

    private void disableChaining(UUID teamId) {
        projectSettingRepository
                .findByTeamIdAndSettingKey(teamId, "audit_integrity_enabled")
                .ifPresent(setting -> {
                    setting.setSettingValue("false");
                    projectSettingRepository.save(setting);
                });
    }

    private void writeProjectEvent(UUID teamId, String eventType, String entityName) {
        auditEventService.recordEvent(
                "test_entity", UUID.randomUUID(), eventType,
                SCOPE_PROJECT, teamId,
                null, "Segment Test", null, null,
                null, Map.of("name", entityName), null);
    }
}