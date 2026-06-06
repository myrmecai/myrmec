package ai.myrmec.engine.snapshot;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface ExecutionSnapshotRepository extends JpaRepository<ExecutionSnapshot, UUID> {

    /** Most-recent-first replay timeline for a single conversation. */
    List<ExecutionSnapshot> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    List<ExecutionSnapshot> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    long countByEventType(String eventType);

    /** Inputs to the (future) snapshot retention pruner. */
    List<ExecutionSnapshot> findByProjectIdAndCreatedAtBefore(UUID projectId, Instant cutoff);
}
