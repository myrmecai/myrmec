// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@link AuditEvent} rows.
 *
 * <p>Append-only — no save/update/delete operations should be called
 * outside the initial insert. Query methods are read-only.</p>
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long>,
        JpaSpecificationExecutor<AuditEvent> {

    List<AuditEvent> findByEntityTypeAndEntityIdOrderByTimestampDesc(String entityType, UUID entityId);

    List<AuditEvent> findByScopeTypeAndProjectIdOrderByTimestampDesc(String scopeType, UUID projectId);

    List<AuditEvent> findByActorIdOrderByTimestampDesc(UUID actorId);

    List<AuditEvent> findByEventTypeOrderByTimestampDesc(String eventType);

    /**
     * Find all events in a scope ordered by ascending ID — the chain
     * verification walk order. Used by {@link AuditHashChainService#verifyScope}.
     */
    List<AuditEvent> findByScopeTypeAndProjectIdOrderByIdAsc(String scopeType, UUID projectId);

    /**
     * Native update to tamper with an event's hash — used only in tests
     * to simulate DB-level tampering (JPA {@code updatable=false} prevents
     * this via the entity manager).
     */
    @Modifying
    @Query(value = "UPDATE audit_events SET event_hash = :hash WHERE id = :id", nativeQuery = true)
    void updateEventHashNative(@org.springframework.data.repository.query.Param("id") Long id,
                                @org.springframework.data.repository.query.Param("hash") String hash);
}