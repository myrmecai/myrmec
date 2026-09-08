package ai.myrmec.engine.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for TaskAttempt entities.
 */
public interface TaskAttemptRepository extends JpaRepository<TaskAttempt, UUID> {

    /**
     * Find all attempts for a task, ordered by attempt number.
     */
    List<TaskAttempt> findByTaskIdOrderByAttemptNumberAsc(UUID taskId);

    /** All attempts of a task (unordered view for contract assertions). */
    List<TaskAttempt> findByTaskId(UUID taskId);

    /**
     * Find the latest attempt for a task.
     */
    Optional<TaskAttempt> findFirstByTaskIdOrderByAttemptNumberDesc(UUID taskId);

    /**
     * Pessimistic row lock for orchestration outcome application (§16.6):
     * the result-identity stamp and state tuple land under this lock.
     */
    @org.springframework.data.jpa.repository.Lock(
            jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<TaskAttempt> findWithLockById(UUID id);

    /**
     * Find a specific attempt by task and attempt number.
     */
    Optional<TaskAttempt> findByTaskIdAndAttemptNumber(UUID taskId, Integer attemptNumber);

    /**
     * Find all running attempts for an agent instance.
     */
    List<TaskAttempt> findByAgentInstanceIdAndStatus(UUID agentInstanceId, AttemptStatus status);

    /** Phase 9d agent health: count active task attempts per instance. */
    long countByAgentInstanceIdAndStatus(UUID agentInstanceId, AttemptStatus status);

    /**
     * Count attempts that count toward retry limit (COMPLETED or FAILED, not ABANDONED/SKIPPED).
     */
    @Query("""
        SELECT COUNT(a) FROM TaskAttempt a
        WHERE a.task.id = :taskId
        AND a.status IN (ai.myrmec.engine.workflow.AttemptStatus.COMPLETED, ai.myrmec.engine.workflow.AttemptStatus.FAILED)
        """)
    int countRetryableAttempts(@Param("taskId") UUID taskId);

    /**
     * Find all attempts for tasks in a request.
     */
    @Query("""
        SELECT a FROM TaskAttempt a
        WHERE a.task.request.id = :requestId
        ORDER BY a.task.id, a.attemptNumber
        """)
    List<TaskAttempt> findByRequestId(@Param("requestId") UUID requestId);

    /**
     * Find all running attempts for tasks in a request.
     */
    @Query("""
        SELECT a FROM TaskAttempt a
        WHERE a.task.request.id = :requestId
        AND a.status = ai.myrmec.engine.workflow.AttemptStatus.RUNNING
        """)
    List<TaskAttempt> findRunningAttemptsByRequestId(@Param("requestId") UUID requestId);
}
