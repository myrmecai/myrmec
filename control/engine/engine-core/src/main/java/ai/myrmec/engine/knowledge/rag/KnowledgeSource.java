package ai.myrmec.engine.knowledge.rag;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Knowledge source — one external system feeding chunks into a {@link KnowledgeBase}.
 *
 * <p>{@link #connectorType} matches {@code KnowledgeSourceConnector.type()};
 * engine resolves the connector bean at sync time. {@link #syncSchedule} is a
 * cron expression for the scheduled-sync executor; null means manual sync only.
 * Last-sync bookkeeping fields drive the KB management UI's source status row.</p>
 */
@Entity
@Table(name = "knowledge_sources")
@Getter
@Setter
@NoArgsConstructor
public class KnowledgeSource {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "knowledge_base_id", nullable = false)
    private UUID knowledgeBaseId;

    /** Matches {@code KnowledgeSourceConnector.type()}. */
    @Column(name = "connector_type", nullable = false, length = 50)
    private String connectorType;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "uri", nullable = false, length = 2000)
    private String uri;

    /** Connector-specific JSON config (auth token ref, prefix, depth, ...). */
    @Column(name = "config_json", columnDefinition = "text")
    private String configJson;

    /** Cron expression; null = manual sync only. */
    @Column(name = "sync_schedule", length = 100)
    private String syncSchedule;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    @Column(name = "last_sync_status", length = 20)
    private String lastSyncStatus;

    @Column(name = "last_sync_chunks")
    private Long lastSyncChunks;

    @Column(name = "last_sync_error_count")
    private Integer lastSyncErrorCount;

    @Column(name = "last_sync_duration_ms")
    private Long lastSyncDurationMs;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
