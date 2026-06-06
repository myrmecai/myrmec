package ai.myrmec.engine.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface AuditLogEntryRepository extends JpaRepository<AuditLogEntry, UUID> {

    /**
     * Filter rows for the audit browser UI. All filter args are
     * optional — a null parameter widens the result set rather than
     * forcing the caller to build a dynamic query.
     */
    @Query("""
            select e from AuditLogEntry e
            where (:actorUserId is null or e.actorUserId = :actorUserId)
              and (:action is null or e.action = :action)
              and (:resourceType is null or e.resourceType = :resourceType)
              and (:resourceId is null or e.resourceId = :resourceId)
              and (:since is null or e.createdAt >= :since)
              and (:until is null or e.createdAt <= :until)
            order by e.createdAt desc
            """)
    Page<AuditLogEntry> search(
            @Param("actorUserId") UUID actorUserId,
            @Param("action") String action,
            @Param("resourceType") String resourceType,
            @Param("resourceId") UUID resourceId,
            @Param("since") Instant since,
            @Param("until") Instant until,
            Pageable pageable);
}
