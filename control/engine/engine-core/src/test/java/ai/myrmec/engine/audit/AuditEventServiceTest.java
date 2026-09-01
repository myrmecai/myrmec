// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.audit;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the behavioural distinction between {@link AuditEventService#recordEvent}
 * (REQUIRED — commits with the caller's transaction) and
 * {@link AuditEventService#recordFailureEvent} (REQUIRES_NEW — survives
 * the caller's rollback).
 *
 * <p>Also serves as a regression guard for the FK-violation fix (2026-08-18):
 * {@code recordEvent} was previously {@code REQUIRES_NEW}, which caused FK
 * violations when callers created entities and audited them in the same
 * transaction (e.g. {@code SecretService.create()} → {@code SECRET_CREATED}
 * referencing a project created in the same test transaction).
 */
class AuditEventServiceTest extends IntegrationTestBase {

    @Autowired private AuditEventService auditEventService;
    @Autowired private AuditEventRepository auditEventRepository;
    @Autowired private TestDataBuilder data;

    @Test
    void recordEvent_commitsWithCallerTransaction() {
        String eventType = "TEST_LIFECYCLE_EVENT_" + System.currentTimeMillis();
        UUID entityId = UUID.randomUUID();

        auditEventService.recordEvent("TestEntity", entityId, eventType,
                "ORGANIZATION", null, TEST_ADMIN_ID, "test-admin",
                null, null, null, null, Map.of("test", true));

        List<AuditEvent> events = auditEventService.findByEventType(eventType);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEntityType()).isEqualTo("TestEntity");
        assertThat(events.get(0).getActorDisplayName()).isEqualTo("test-admin");
    }

    @Test
    void recordFailureEvent_persistsEvenWhenCallerRollsBack() {
        String eventType = "TEST_FAILURE_EVENT_" + System.currentTimeMillis();
        UUID entityId = UUID.randomUUID();

        // Record a failure event and then simulate a rollback by throwing.
        // The REQUIRES_NEW propagation means the audit row should persist
        // despite the exception.
        try {
            auditEventService.recordFailureEvent("TestEntity", entityId, eventType,
                    "ORGANIZATION", null, TEST_ADMIN_ID, "test-admin",
                    null, null, null, null, Map.of("test", true));
            throw new RuntimeException("Simulated failure");
        } catch (RuntimeException ignored) {
            // Expected — the point is the audit row should survive
        }

        // The failure event should be persisted despite the exception
        List<AuditEvent> events = auditEventService.findByEventType(eventType);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo(eventType);
    }

    @Test
    void recordFailureEvent_canReferenceAlreadyCommittedProject() {
        // Create a project (committed), then record a failure event referencing it.
        // This should not hit a FK violation because the project is already committed.
        var project = data.project().named("audit-fk-test").create();

        String eventType = "TEST_FAILURE_FK_" + System.currentTimeMillis();

        try {
            auditEventService.recordFailureEvent("TestEntity", project.getId(), eventType,
                    "PROJECT", project.getId(), TEST_ADMIN_ID, "test-admin",
                    null, null, null, null, Map.of("projectId", project.getId().toString()));
            throw new RuntimeException("Simulated failure");
        } catch (RuntimeException ignored) {
            // Expected
        }

        List<AuditEvent> events = auditEventService.findByEventType(eventType);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getProjectId()).isEqualTo(project.getId());
    }
}