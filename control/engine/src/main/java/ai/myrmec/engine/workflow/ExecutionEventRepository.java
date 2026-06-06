package ai.myrmec.engine.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for ExecutionEvent entities.
 */
public interface ExecutionEventRepository extends JpaRepository<ExecutionEvent, UUID> {

    /**
     * Find all events for a task, ordered by timestamp.
     */
    List<ExecutionEvent> findByTaskIdOrderByCreatedAtAsc(UUID taskId);

    /**
     * Find events for a task after a given timestamp (for incremental fetching).
     */
    List<ExecutionEvent> findByTaskIdAndCreatedAtAfterOrderByCreatedAtAsc(UUID taskId, Instant after);

    /**
     * Find all events for a request (all tasks in the request).
     */
    @Query("""
        SELECT e FROM ExecutionEvent e
        WHERE e.taskId IN (SELECT t.id FROM WorkflowTask t WHERE t.request.id = :requestId)
        ORDER BY e.createdAt ASC
        """)
    List<ExecutionEvent> findByRequestId(@Param("requestId") UUID requestId);

    /**
     * Find events for a request after a given timestamp.
     */
    @Query("""
        SELECT e FROM ExecutionEvent e
        WHERE e.taskId IN (SELECT t.id FROM WorkflowTask t WHERE t.request.id = :requestId)
        AND e.createdAt > :after
        ORDER BY e.createdAt ASC
        """)
    List<ExecutionEvent> findByRequestIdAndCreatedAtAfter(
            @Param("requestId") UUID requestId, 
            @Param("after") Instant after);

    /**
     * Count events for a task.
     */
    long countByTaskId(UUID taskId);

    /**
     * Delete all events for a task.
     */
    void deleteByTaskId(UUID taskId);

    // ==========================================================================
    // Attempt-based queries
    // ==========================================================================

    /**
     * Find all events for an attempt, ordered by timestamp.
     */
    List<ExecutionEvent> findByAttemptIdOrderByCreatedAtAsc(UUID attemptId);

    /**
     * Find events for an attempt after a given timestamp (for incremental fetching).
     */
    List<ExecutionEvent> findByAttemptIdAndCreatedAtAfterOrderByCreatedAtAsc(UUID attemptId, Instant after);

    /**
     * Count events for an attempt.
     */
    long countByAttemptId(UUID attemptId);
}
