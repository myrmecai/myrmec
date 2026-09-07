// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link OrchestrationDispatch} (design §16.3).
 */
@Repository
public interface OrchestrationDispatchRepository extends JpaRepository<OrchestrationDispatch, UUID> {

    /** Pessimistic lock for the accept flip and relay state changes. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OrchestrationDispatch> findWithLockByDispatchId(UUID dispatchId);

    /** Pending/accepted dispatches for a run, oldest first (relay scan). */
    List<OrchestrationDispatch> findByRunIdOrderByCreatedAtAsc(UUID runId);
}