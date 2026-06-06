package ai.myrmec.engine.spi.connector;

import jakarta.validation.constraints.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Outcome of a {@link KnowledgeSourceConnector#sync} call. Engine persists
 * the result into the {@code knowledge_sources} row (last sync timestamp,
 * status, error count) for the KB management UI.
 *
 * @param status          non-null end state (SUCCESS, PARTIAL, FAILED).
 * @param chunksEmitted   total number of chunks the connector handed to the
 *                        {@link ConnectorContext#chunkSink()} during this run.
 * @param errors          non-null per-resource error messages (empty when status
 *                        == SUCCESS). Connectors should add a single string per
 *                        skipped resource for the operator runbook.
 * @param duration        non-null wall-clock duration of the sync.
 * @param completedAt     non-null instant the sync finished.
 */
public record SyncResult(
    @NotNull Status status,
    long chunksEmitted,
    @NotNull List<String> errors,
    @NotNull Duration duration,
    @NotNull Instant completedAt
) {

    public SyncResult {
        errors = errors == null ? List.of() : Collections.unmodifiableList(errors);
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(completedAt, "completedAt");
    }

    public enum Status {
        /** Every resource synced successfully. */
        SUCCESS,
        /** Some resources synced, others failed; see {@link #errors()}. */
        PARTIAL,
        /** Sync aborted before producing usable output. */
        FAILED
    }
}
