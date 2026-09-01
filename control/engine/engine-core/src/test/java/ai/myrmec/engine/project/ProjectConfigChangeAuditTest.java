// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.project;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.TestDataFactory;
import ai.myrmec.engine._system.common.DomainConstants.AuditAction;
import ai.myrmec.engine._system.common.ResourceType;
import ai.myrmec.engine.audit.AuditEvent;
import ai.myrmec.engine.project.dto.UpdateProjectRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #121 — Project configuration change audit (field-level change history).
 *
 * <p>Every change to a project's runtime-affecting settings is recorded in
 * {@code audit_events} with per-field old/new values, answering "who changed
 * the governance posture / HITL toggle / workspace config, and when".</p>
 */
@Tag("SG9")
class ProjectConfigChangeAuditTest extends IntegrationTestBase {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectSettingService projectSettingService;

    @Test
    void projectUpdateRecordsChangedFieldsWithOldAndNew() {
        Project p = projectRepository.save(TestDataFactory.projectBuilder("cfg-audit-" + UUID.randomUUID()).build());

        UpdateProjectRequest req = new UpdateProjectRequest();
        req.setAutoHitlOnDestructive(true);
        req.setAttachmentsEnabled(false);
        projectService.update(p.getId(), req);

        List<AuditEvent> events = auditEventRepository
                .findByEntityTypeAndEntityIdOrderByTimestampDesc(ResourceType.PROJECT, p.getId());
        assertThat(events).isNotEmpty();

        AuditEvent change = events.stream()
                .filter(e -> AuditAction.CONFIG_CHANGED.equals(e.getEventType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no CONFIG_CHANGED event recorded"));

        // Field-level old/new — before/after snapshots carry only changed fields.
        Map<String, Object> after = change.getAfterSnapshot();
        Map<String, Object> before = change.getBeforeSnapshot();
        assertThat(after).containsEntry("autoHitlOnDestructive", true);
        assertThat(after).containsEntry("attachmentsEnabled", false);
        // Unchanged fields are not recorded.
        assertThat(after).doesNotContainKey("workspaceRepoUrl");
    }

    @Test
    void noOpUpdateRecordsNothing() {
        Project p = projectRepository.save(TestDataFactory.projectBuilder("cfg-noop-" + UUID.randomUUID()).build());

        // Update with no field set → no change → no audit event.
        projectService.update(p.getId(), new UpdateProjectRequest());

        boolean any = auditEventRepository
                .findByEntityTypeAndEntityIdOrderByTimestampDesc(ResourceType.PROJECT, p.getId())
                .stream()
                .anyMatch(e -> AuditAction.CONFIG_CHANGED.equals(e.getEventType()));
        assertThat(any).as("a no-op update must not record a config-change event").isFalse();
    }

    @Test
    void settingChangeRecordsKeyOldAndNew() {
        // audit_events.project_id has an FK to projects(id) — the setting must
        // belong to a real project, as it always does in production.
        Project p = projectRepository.save(TestDataFactory.projectBuilder("cfg-set-" + UUID.randomUUID()).build());
        UUID projectId = p.getId();

        projectSettingService.update(projectId, "attachment_inline_ratio_max", "0.5", TEST_ADMIN_ID);

        List<AuditEvent> events = auditEventRepository
                .findByScopeTypeAndProjectIdOrderByTimestampDesc("PROJECT", projectId);
        assertThat(events).isNotEmpty();

        AuditEvent change = events.stream()
                .filter(e -> AuditAction.SETTING_CHANGED.equals(e.getEventType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no SETTING_CHANGED event recorded"));

        assertThat((Map<String, Object>) change.getAfterSnapshot())
                .containsEntry("key", "attachment_inline_ratio_max")
                .containsEntry("newValue", "0.5");
    }
}
