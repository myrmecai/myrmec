// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.audit;

import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Audit Event Service — append-only write, query by entity/time/actor.
 *
 * <p>This is the generic event recorder used by all versioned entity
 * services. The service only supports insert and read — no update or
 * delete, as the table is append-only by design.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditEventService {

    private final AuditEventRepository repository;

    /**
     * Record an audit event. This is the primary write method.
     *
     * @param entityType    the entity type (e.g., "connection_config", "instruction_asset")
     * @param entityId      the parent row id
     * @param eventType     the event type (e.g., "CREATED", "PUBLISHED", "DISABLED")
     * @param scopeType     the scope type ("ORGANIZATION" or "PROJECT")
     * @param projectId     the project id (null for org-scoped events)
     * @param actorId       the user id who triggered the event
     * @param actorDisplayName the user's display name (denormalized for survival after deletion)
     * @param versionId     the version row id (null for parent-only events)
     * @param reasonCode    optional reason code (e.g., "ADMIN_DISABLED")
     * @param beforeSnapshot changed fields before the event (null if N/A)
     * @param afterSnapshot  changed fields after the event (null if N/A)
     * @param metadata      extra context (null if N/A)
     * @return the persisted audit event
     */
    @Transactional
    public AuditEvent recordEvent(
            String entityType,
            UUID entityId,
            String eventType,
            String scopeType,
            UUID projectId,
            UUID actorId,
            String actorDisplayName,
            UUID versionId,
            String reasonCode,
            Map<String, Object> beforeSnapshot,
            Map<String, Object> afterSnapshot,
            Map<String, Object> metadata) {

        AuditEvent event = new AuditEvent();
        event.setEventType(eventType);
        event.setEntityType(entityType);
        event.setEntityId(entityId);
        event.setVersionId(versionId);
        event.setScopeType(scopeType);
        event.setProjectId(projectId);
        event.setActorId(actorId);
        event.setActorDisplayName(actorDisplayName);
        event.setReasonCode(reasonCode);
        event.setBeforeSnapshot(beforeSnapshot);
        event.setAfterSnapshot(afterSnapshot);
        event.setMetadata(metadata);

        log.info("Recording audit event: type={}, entity={}/{}, actor={}",
                eventType, entityType, entityId, actorDisplayName);

        return repository.save(event);
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> findByEntity(String entityType, UUID entityId) {
        return repository.findByEntityTypeAndEntityIdOrderByTimestampDesc(entityType, entityId);
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> findByScope(String scopeType, UUID projectId) {
        return repository.findByScopeTypeAndProjectIdOrderByTimestampDesc(scopeType, projectId);
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> findByActor(UUID actorId) {
        return repository.findByActorIdOrderByTimestampDesc(actorId);
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> findByEventType(String eventType) {
        return repository.findByEventTypeOrderByTimestampDesc(eventType);
    }

    @Transactional(readOnly = true)
    public AuditEvent findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("AuditEvent", id));
    }
}