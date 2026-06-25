package ai.myrmec.engine.knowledge.rag.dto;

import ai.myrmec.engine.spi.connector.SyncResult;

import java.time.Instant;
import java.util.List;

/**
 * Wire representation of a manual-sync outcome.
 *
 * <p>A failed sync is reported with HTTP 200 and {@code status=FAILED} so the
 * UI can render the connector error inline (the source's last-sync bookkeeping
 * is persisted regardless).</p>
 */
public record SyncResultResponse(
        String status,
        long chunksEmitted,
        int errorCount,
        List<String> errors,
        long durationMs,
        Instant completedAt
) {

    public static SyncResultResponse from(SyncResult result) {
        return new SyncResultResponse(
                result.status().name(),
                result.chunksEmitted(),
                result.errors().size(),
                result.errors(),
                result.duration().toMillis(),
                result.completedAt());
    }

    /** Build a FAILED result from a connector error message. */
    public static SyncResultResponse failed(String message) {
        return new SyncResultResponse(
                SyncResult.Status.FAILED.name(),
                0L,
                1,
                List.of(message == null ? "Sync failed" : message),
                0L,
                Instant.now());
    }
}
