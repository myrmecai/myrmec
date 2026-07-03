// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@link AuditEvent} rows.
 *
 * <p>Append-only — no save/update/delete operations should be called
 * outside the initial insert. Query methods are read-only.</p>
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    List<AuditEvent> findByEntityTypeAndEntityIdOrderByTimestampDesc(String entityType, UUID entityId);

    List<AuditEvent> findByScopeTypeAndProjectIdOrderByTimestampDesc(String scopeType, UUID projectId);

    List<AuditEvent> findByActorIdOrderByTimestampDesc(UUID actorId);

    List<AuditEvent> findByEventTypeOrderByTimestampDesc(String eventType);
}