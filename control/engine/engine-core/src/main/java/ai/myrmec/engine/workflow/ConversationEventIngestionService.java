// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import ai.myrmec.engine.conversation.ConversationRepository;
import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.inference.execution.SessionExecution;
import ai.myrmec.engine.inference.execution.SessionExecutionRepository;
import ai.myrmec.engine.spi.quota.QuotaPolicyEngine;
import ai.myrmec.engine.spi.quota.QuotaResourceType;
import ai.myrmec.engine.spi.quota.QuotaScope;
import ai.myrmec.engine.websocket.host.payload.ExecutionEventPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable sink for Agent {@code execution.event} frames on CONVERSATION
 * executions (#139, protocol §8.4/§12.3). Persists the full §8.4 union as
 * {@code execution_events} rows with {@code kind=CONVERSATION}
 * ({@code task_id}/{@code attempt_id} null — conversations have no
 * orchestration attempt tuple), deduped by the Agent's deterministic
 * {@code eventId} ({@code source_event_id}) so the at-least-once §12.3
 * resend never double-inserts.
 *
 * <p>Correlation validates the {@code executionId} against the
 * {@code session_executions} row (the execution knows its session, the
 * session its conversation + project) — NOT against an attempt.
 * TOKEN_USAGE events additionally feed
 * {@link QuotaPolicyEngine#recordConsumption} per call, so conversation
 * quota attribution no longer depends only on the terminal
 * {@code usage} block.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationEventIngestionService {

    private final ExecutionEventRepository eventRepository;
    private final SessionExecutionRepository executionRepository;
    private final SessionRepository sessionRepository;
    private final ConversationRepository conversationRepository;
    private final QuotaPolicyEngine quotaPolicyEngine;

    public enum IngestResult {
        /** New row inserted; the caller should ack. */
        INSERTED,
        /** Replay of a known {@code source_event_id}; nothing persisted; the caller should ack. */
        REPLAY
    }

    /**
     * Ingest one §8.4 event frame from a conversation execution.
     *
     * @param executionId the CONVERSATION session-execution the event belongs to
     * @param payload     the §8.4 event payload (eventId, eventType, occurredAt, data)
     * @return the outcome of ingestion
     * @throws IllegalArgumentException when the execution is unknown or not a conversation execution
     * @throws IllegalStateException    when a replay carries a conflicting payload
     */
    @Transactional
    public IngestResult ingest(UUID executionId, ExecutionEventPayload payload) {
        if (payload == null || payload.eventId() == null) {
            throw new IllegalArgumentException("execution.event requires an eventId");
        }
        UUID sourceEventId = payload.eventId();

        // Correlation: validate against the execution row (NOT the
        // orchestration attempt tuple — conversations have neither task
        // nor attempt).
        SessionExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown execution: " + executionId));
        if (!"CONVERSATION".equals(execution.getServiceType())) {
            throw new IllegalArgumentException(
                    "Execution " + executionId + " is not a conversation execution");
        }
        Session sess = sessionRepository.findById(execution.getSessionId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Execution " + executionId + " has no session row"));

        // Idempotency: an existing row with the same source event is a replay…
        var existing = eventRepository.findBySourceEventId(sourceEventId);
        if (existing.isPresent()) {
            ExecutionEvent row = existing.get();
            if (samePayload(row, payload)) {
                return IngestResult.REPLAY;
            }
            log.warn("Conflicting duplicate conversation event {} — rejecting", sourceEventId);
            throw new IllegalStateException(
                    "Conflicting duplicate conversation event: " + sourceEventId);
        }

        ExecutionEvent event = new ExecutionEvent();
        event.setTaskId(null);
        event.setAttemptId(null);
        event.setKind("CONVERSATION");
        event.setSource(LogSource.AGENT);
        event.setSourceEventId(sourceEventId);
        event.setMessage(eventTypeOf(payload.eventType()));
        event.setData(payload.data());
        event.setCreatedAt(payload.occurredAt() != null ? payload.occurredAt() : Instant.now());
        event.setEventType(mapEventType(eventTypeOf(payload.eventType()), event));
        eventRepository.save(event);

        if (event.getEventType() == EventType.TOKEN_USAGE) {
            attributeUsage(sess, payload.data());
        }
        return IngestResult.INSERTED;
    }

    /** Tolerant event-type mapping: unknown §8.4 values persist as LOG with a note in data. */
    private EventType mapEventType(String eventType, ExecutionEvent event) {
        EventType mapped = EventType.parse(eventType);
        if (mapped != null) {
            return mapped;
        }
        log.warn("Unknown conversation eventType '{}' for event {} — persisting as LOG",
                eventType, event.getSourceEventId());
        Map<String, Object> data = new LinkedHashMap<>();
        if (event.getData() != null) {
            data.putAll(event.getData());
        }
        data.put("unknownEventType", eventType);
        event.setData(data);
        return EventType.LOG;
    }

    /** The eventType string is kept as the message so ORCHESTRATION-shaped types aren't lost. */
    private String eventTypeOf(String eventType) {
        return eventType == null ? "LOG" : eventType;
    }

    /**
     * §12.3-adjacent usage attribution: a TOKEN_USAGE event feeds the
     * existing quota seam per call. The conversation is the session's
     * {@code refId}; the session row owns {@code projectId}.
     */
    private void attributeUsage(Session sess, Map<String, Object> data) {
        long tokens = tokenCount(data);
        if (tokens <= 0) {
            return;
        }
        try {
            UUID assistantId = assistantOf(sess.getRefId()).orElse(null);
            if (assistantId != null) {
                quotaPolicyEngine.recordConsumption(
                        QuotaScope.SERVICE, assistantId, QuotaResourceType.TOKENS, tokens);
                log.debug("Attributed {} token consumption to assistant {} (conversation {})",
                        tokens, assistantId, sess.getRefId());
            } else {
                quotaPolicyEngine.recordConsumption(
                        QuotaScope.PROJECT, sess.getProjectId(), QuotaResourceType.TOKENS, tokens);
                log.debug("Attributed {} token consumption to project {} (conversation {})",
                        tokens, sess.getProjectId(), sess.getRefId());
            }
        } catch (Exception e) {
            log.warn("Failed to attribute token usage for conversation {}: {}",
                    sess.getRefId(), e.getMessage());
        }
    }

    /**
     * Resolve the conversation's serving assistant id (the SERVICE-scope
     * attribution key). Conversations carry the assistant pin directly.
     */
    private Optional<UUID> assistantOf(UUID conversationId) {
        try {
            return conversationRepository.findById(conversationId)
                    .map(ai.myrmec.engine.conversation.Conversation::getAssistantId)
                    .filter(java.util.Objects::nonNull);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** The §8.4 TOKEN_USAGE data shape: { model, promptTokens, completionTokens, totalTokens }. */
    static long tokenCount(Map<String, Object> data) {
        if (data == null) {
            return 0L;
        }
        Object total = data.get("totalTokens");
        if (total instanceof Number n && n.longValue() > 0) {
            return n.longValue();
        }
        return longOf(data.get("promptTokens")) + longOf(data.get("completionTokens"));
    }

    private static long longOf(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private boolean samePayload(ExecutionEvent row, ExecutionEventPayload payload) {
        if (!java.util.Objects.equals(row.getMessage(), eventTypeOf(payload.eventType()))) {
            return false;
        }
        Map<String, Object> stored = row.getData() == null ? Map.of() : row.getData();
        Map<String, Object> incoming = payload.data() == null ? Map.of() : payload.data();
        return java.util.Objects.equals(stored, incoming);
    }
}