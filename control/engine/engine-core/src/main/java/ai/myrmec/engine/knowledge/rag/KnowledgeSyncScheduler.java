package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine.spi.connector.ConnectorException;
import ai.myrmec.engine.spi.connector.SyncResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Scheduled-sync executor (#21 shared infra). Periodically scans enabled
 * knowledge sources that carry a {@code sync_schedule} cron expression and
 * triggers {@link ConnectorDispatcher#sync} for any source whose next cron fire
 * time has elapsed since its last sync.
 *
 * <p>"Due" is computed from the source's {@code lastSyncAt} (or {@code createdAt}
 * for a never-synced source): the next cron occurrence after that baseline must
 * be at or before now. Because {@link ConnectorDispatcher} advances
 * {@code lastSyncAt} on both success and failure, a source cannot busy-loop —
 * the baseline always moves forward after an attempt.</p>
 *
 * <p>Each source is synced in {@link ConnectorDispatcher}'s own transaction; a
 * failure on one source is logged and never aborts the rest of the scan.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class KnowledgeSyncScheduler {

    private final KnowledgeSourceRepository knowledgeSourceRepository;
    private final ConnectorDispatcher connectorDispatcher;

    /** Disabled in tests that don't want timing pressure. */
    @Value("${myrmec.knowledge.sync-scheduler.enabled:true}")
    private boolean enabled;

    /**
     * Production default 60s; tests can override to a tighter cadence. The
     * cron granularity of individual sources gates how often a source actually
     * syncs — this only bounds detection latency.
     */
    @Scheduled(fixedRateString = "${myrmec.knowledge.sync-scheduler.interval-ms:60000}")
    public void scan() {
        if (!enabled) {
            return;
        }
        runDueSyncs();
    }

    /**
     * Sync every enabled source that is currently due — both event-requested
     * ones (#25a push, highest priority) and cron-due scheduled ones (#21).
     * Exposed (package-private) so tests can drive it deterministically without
     * waiting for the {@link Scheduled} cadence.
     */
    void runDueSyncs() {
        Instant now = Instant.now();
        Set<UUID> handled = new HashSet<>();

        // 1. Event-driven (webhook) requests first. The stamp is cleared before
        //    dispatch so a request arriving mid-sync is preserved for next tick
        //    (at-least-once), and so a failing source can't busy-loop.
        for (KnowledgeSource source : safeFind(
                knowledgeSourceRepository::findByEnabledTrueAndSyncRequestedAtIsNotNull)) {
            clearSyncRequest(source);
            dispatch(source, "Webhook-requested");
            handled.add(source.getId());
        }

        // 2. Cron-due scheduled sources (skip any already synced via a request).
        for (KnowledgeSource source : safeFind(
                knowledgeSourceRepository::findByEnabledTrueAndSyncScheduleIsNotNull)) {
            if (handled.contains(source.getId()) || !isDue(source, now)) {
                continue;
            }
            dispatch(source, "Scheduled");
        }
    }

    private List<KnowledgeSource> safeFind(Supplier<List<KnowledgeSource>> query) {
        try {
            return query.get();
        } catch (Exception e) {
            log.warn("Knowledge sync scheduler query failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /** Clear a source's pending re-sync stamp in its own committed transaction. */
    private void clearSyncRequest(KnowledgeSource source) {
        try {
            source.setSyncRequestedAt(null);
            knowledgeSourceRepository.save(source);
        } catch (Exception e) {
            log.warn("Failed to clear sync request on source {}: {}", source.getId(), e.getMessage());
        }
    }

    private void dispatch(KnowledgeSource source, String trigger) {
        try {
            SyncResult result = connectorDispatcher.sync(source.getId());
            log.info("{} sync of source {} ({}): {} -> {} chunk(s), {} error(s)",
                    trigger, source.getId(), source.getName(), result.status(),
                    result.chunksEmitted(), result.errors().size());
        } catch (ConnectorException e) {
            log.warn("{} sync of source {} ({}) failed: {}",
                    trigger, source.getId(), source.getName(), e.getMessage());
        } catch (Exception e) {
            log.warn("{} sync of source {} ({}) errored: {}",
                    trigger, source.getId(), source.getName(), e.getMessage(), e);
        }
    }

    /**
     * Whether {@code source} is due to sync at {@code now}. A source with a
     * blank or unparseable cron is never due (the bad expression is logged so
     * an operator can correct it). The baseline is the last sync time, falling
     * back to creation time for a source that has never synced.
     */
    boolean isDue(KnowledgeSource source, Instant now) {
        String schedule = source.getSyncSchedule();
        if (schedule == null || schedule.isBlank()) {
            return false;
        }
        CronExpression cron;
        try {
            cron = CronExpression.parse(schedule.trim());
        } catch (IllegalArgumentException e) {
            log.warn("Knowledge source {} has invalid cron '{}': {}",
                    source.getId(), schedule, e.getMessage());
            return false;
        }
        Instant baseline = source.getLastSyncAt() != null ? source.getLastSyncAt() : source.getCreatedAt();
        if (baseline == null) {
            baseline = now;
        }
        ZonedDateTime next = cron.next(baseline.atZone(ZoneOffset.UTC));
        return next != null && !next.toInstant().isAfter(now);
    }
}
