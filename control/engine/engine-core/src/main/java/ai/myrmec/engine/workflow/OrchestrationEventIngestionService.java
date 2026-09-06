// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Idempotent ingestion of Agent {@code orchestration.event} frames (design
 * §16.3/§21). Validates the correlation tuple against engine rows, inserts
 * one {@code EventType.ORCHESTRATION} row per deterministic Agent eventId
 * (unique {@code source_event_id}; replay is a no-op), rejects conflicting
 * duplicate payloads, and preserves dispatch-local ordering through the
 * unique {@code (attempt_id, sequence_number)} index. Events are exposed
 * through the existing SSE stream.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrchestrationEventIngestionService {

    private final ExecutionEventRepository eventRepository;
    private final TaskAttemptRepository attemptRepository;

    /**
     * Ingest one orchestration event frame.
     *
     * @param dispatchId   the dispatch the event belongs to (attempt UUID)
     * @param sourceEventId the Agent's deterministic event ID — idempotency key
     * @param sequence      dispatch-local strictly-monotonic sequence
     * @param type         §21 closed-union event type (WORKER_STARTED, …)
     * @param envelope      the redacted envelope fields (workerName, usage, …)
     * @return the outcome of ingestion
     */
    @Transactional
    public IngestResult ingest(UUID dispatchId, UUID sourceEventId, long sequence,
                               String type, Map<String, Object> envelope) {
        // Correlation: the dispatch is the attempt — validated from engine rows.
        TaskAttempt attempt = attemptRepository.findById(dispatchId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown dispatch: " + dispatchId));
        UUID taskId = attempt.getTask().getId();

        // Idempotency: an existing row with the same source event is a replay…
        var existing = eventRepository.findBySourceEventId(sourceEventId);
        if (existing.isPresent()) {
            ExecutionEvent row = existing.get();
            if (samePayload(row, type, sequence, envelope)) {
                return IngestResult.REPLAY;
            }
            log.warn("Conflicting duplicate orchestration event {} — rejecting",
                    sourceEventId);
            throw new IllegalStateException(
                    "Conflicting duplicate orchestration event: " + sourceEventId);
        }

        // Ordering: (attempt_id, sequence_number) uniqueness — a different
        // event claiming the same slot is a protocol violation.
        var bySlot = eventRepository.findByAttemptIdAndSequenceNumber(dispatchId, sequence);
        if (bySlot.isPresent() && !bySlot.get().getId().equals(sourceEventId)) {
            throw new IllegalStateException(
                    "Sequence slot " + sequence + " already held by another event for dispatch "
                            + dispatchId);
        }

        ExecutionEvent event = new ExecutionEvent();
        event.setId(sourceEventId); // deterministic ID == engine row id
        event.setTaskId(taskId);
        event.setAttemptId(dispatchId);
        event.setEventType(EventType.ORCHESTRATION);
        event.setMessage(type);
        event.setData(envelope);
        event.setSource(LogSource.AGENT);
        event.setSourceEventId(sourceEventId);
        event.setSequenceNumber(sequence);
        event.setCreatedAt(Instant.now());
        eventRepository.save(event);
        return IngestResult.INSERTED;
    }

    private boolean samePayload(ExecutionEvent row, String type, long sequence,
                                Map<String, Object> envelope) {
        if (!java.util.Objects.equals(row.getMessage(), type)) return false;
        if (row.getSequenceNumber() == null || row.getSequenceNumber() != sequence) return false;
        return java.util.Objects.equals(row.getData(), envelope);
    }

    public enum IngestResult {
        INSERTED,
        REPLAY
    }
}