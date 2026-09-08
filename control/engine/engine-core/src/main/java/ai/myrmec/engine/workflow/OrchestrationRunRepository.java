// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link OrchestrationRun} (design §16.7).
 */
@Repository
public interface OrchestrationRunRepository extends JpaRepository<OrchestrationRun, UUID> {

    /**
     * Pessimistic row lock for run-state transitions (affinity, availability,
     * lease, recovery identity). Outcome transactions lock the task attempt
     * row; availability/affinity passes lock this row.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OrchestrationRun> findWithLockById(UUID id);

    /**
     * §16.4 (7): reconnection of the coordinator clears the run's
     * availability condition — all runs coordinated by this instance.
     */
    java.util.List<OrchestrationRun> findByCoordinatorAgentId(UUID coordinatorAgentId);

    /**
     * §16.5 lease renewal: live leases whose deadline the engine keeps
     * in the future (ACTIVE/ACQUIRING/SUSPENDED).
     */
    java.util.List<OrchestrationRun> findByLeaseStateIn(java.util.List<String> leaseStates);
}