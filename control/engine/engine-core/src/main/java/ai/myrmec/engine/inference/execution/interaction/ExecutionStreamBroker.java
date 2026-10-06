// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import ai.myrmec.engine.node.NodeTransport;
import ai.myrmec.engine.workflow.ExecutionEvent;
import ai.myrmec.engine.workflow.ExecutionEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Task 9 (plan §3.5/§4): the execution-keyed stream broker — the §4
 * subscriber ALGORITHM plus the multi-node relay, reusing the
 * ConversationStreamBroker patterns with execution keys and NO
 * conversation row.
 *
 * <p>The §4 subscriber algorithm (plan-verbatim):</p>
 *
 * <pre>
 * authorize -> subscribe and buffer -> read high-water + replay rows
 * -> send rows ordered by streamSequence -> drain buffered IDs above high-water
 * -> live -> reconnect using last durable SSE id
 * </pre>
 *
 * <p><b>Multi-node fan-out</b>: after-commit publications are WAKEUP HINTS
 * (the {@link NodeTransport} execution arm relays a typed execution-stream
 * frame to peer nodes whose subscriber set is non-empty — the receiving
 * side re-enters this broker via {@code deliverRemoteFrameRelay}). The
 * DURABLE cursor is the delivery authority: every wakeup triggers a
 * cursor-ordered fetch from THE COMMITTED ROWS (never relaying the wire
 * frame bytes for durable events), and a shared bounded scheduler CATCHES
 * UP from each subscriber's last delivered sequence — repairing
 * commit-before-publication crashes, lost relay hints, and out-of-order
 * node notifications. Never delivers cursor 12 and silently skips
 * committed cursor 11.</p>
 *
 * <p><b>Slow subscribers</b>: delivery goes through a per-handle
 * bounded-wakeup buffer; a wedged client (buffer full) is DISCONNECTED
 * with cursor replay available at its Last-Event-ID rather than blocking
 * the SDK outbox. The only background resources are the two shared sweep
 * passes on the dedicated bounded {@code executionStreamScheduler} —
 * NO per-SSE-client thread or timer (§4). A wedged (stalled, not
 * throwing) SSE send can therefore stall at most THAT dedicated pool,
 * never the engine's other sweeps.</p>
 */
@Component
@Slf4j
public class ExecutionStreamBroker {

    /** executionId → live sinks tuned to that execution. */
    private final Map<UUID, Set<ExecutionSubscriberHandle>> subscribers =
            new ConcurrentHashMap<>();

    private final ExecutionViewService viewService;
    private final ExecutionEventRepository eventRepository;
    private final ai.myrmec.engine.inference.execution.SessionExecutionRepository
            executionRepository;
    private final NodeTransport nodeTransport;
    private final ObjectMapper objectMapper;
    private final org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
            executionStreamScheduler;
    private final long catchUpIntervalMs;
    private final long heartbeatIntervalMs;
    private final List<java.util.concurrent.ScheduledFuture<?>> sweepFutures =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public ExecutionStreamBroker(
            @org.springframework.context.annotation.Lazy ExecutionViewService viewService,
            ExecutionEventRepository eventRepository,
            ai.myrmec.engine.inference.execution.SessionExecutionRepository
                    executionRepository,
            NodeTransport nodeTransport,
            ObjectMapper objectMapper,
            ExecutionSweepScheduler executionStreamScheduler,
            @Value("${myrmec.execution.stream.catch-up-batch:50}") int catchUpBatch) {
        this.viewService = viewService;
        this.eventRepository = eventRepository;
        this.executionRepository = executionRepository;
        this.nodeTransport = nodeTransport;
        this.objectMapper = objectMapper;
        this.executionStreamScheduler = executionStreamScheduler.pooled();
        this.catchUpIntervalMs = executionStreamScheduler.catchUpIntervalMs();
        this.heartbeatIntervalMs = executionStreamScheduler.heartbeatIntervalMs();
        this.catchUpBatch = Math.max(1, catchUpBatch);
    }

    /** §3.5: one catch-up pass delivers at most this many rows per handle
     * (bounded work; the next pass continues from the advanced cursor). */
    private final int catchUpBatch;

    /**
     * Fix (§4 scheduler wedge): the stream sweeps' DEDICATED bounded
     * scheduler — a 2-thread daemon pool named for the execution stream.
     * Spring Boot's default single-thread {@code @Scheduled} scheduler is
     * shared by every engine sweeper (the dispatch poller, the
     * confirmation sweeper, the heartbeat sweeper, the allocators); one
     * stalled-but-not-throwing SSE send there would wedge ALL of them.
     * The two stream sweeps are MANUALLY scheduled here
     * ({@code scheduleWithFixedDelay}, started in
     * {@link #startSweeps}, cancelled in {@link #stopSweeps}) so a wedge
     * is contained to THIS broker's own sweeps. Zero per-client threads.
     */
    @Component
    @Slf4j
    public static class ExecutionSweepScheduler {

        private final org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
                scheduler = new org.springframework.scheduling.concurrent
                .ThreadPoolTaskScheduler();

        private final long catchUpIntervalMs;
        private final long heartbeatIntervalMs;

        public ExecutionSweepScheduler(
                @Value("${myrmec.execution.stream.catch-up.interval-ms:2000}")
                long catchUpIntervalMs,
                @Value("${myrmec.execution.stream.heartbeat.interval-ms:30000}")
                long heartbeatIntervalMs) {
            this.catchUpIntervalMs = catchUpIntervalMs;
            this.heartbeatIntervalMs = heartbeatIntervalMs;
            scheduler.setPoolSize(2);
            scheduler.setThreadNamePrefix("execution-stream-scheduler-");
            scheduler.setDaemon(true);
            scheduler.setRemoveOnCancelPolicy(true);
            scheduler.initialize();
        }

        org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler pooled() {
            return scheduler;
        }

        long catchUpIntervalMs() {
            return catchUpIntervalMs;
        }

        long heartbeatIntervalMs() {
            return heartbeatIntervalMs;
        }

        @jakarta.annotation.PreDestroy
        void shutdown() {
            scheduler.shutdown();
        }
    }

    /** Start the two shared sweeps on the dedicated scheduler. */
    @PostConstruct
    void startSweeps() {
        sweepFutures.add(executionStreamScheduler.scheduleWithFixedDelay(
                this::scheduledCatchUpSweep,
                java.time.Duration.ofMillis(Math.max(100, catchUpIntervalMs))));
        sweepFutures.add(executionStreamScheduler.scheduleWithFixedDelay(
                this::scheduledHeartbeatSweep,
                java.time.Duration.ofMillis(Math.max(1000, heartbeatIntervalMs))));
    }

    /** Stop the sweeps (the scheduler itself shuts down via its own bean). */
    @jakarta.annotation.PreDestroy
    void stopSweeps() {
        for (java.util.concurrent.ScheduledFuture<?> future : sweepFutures) {
            future.cancel(false);
        }
        sweepFutures.clear();
    }

    /** A subscriber + its pending-wakeup state + delivery cursor. */
    static final class ExecutionSubscriberHandle {
        final ExecutionSubscriber sink;
        /** Per-handle lock so concurrent broadcasts never interleave frames. */
        final Object sendLock = new Object();
        /** The last DURABLE sequence actually delivered to this sink. */
        volatile long lastDeliveredSequence;
        /** Whether the broker already cut this subscriber loose. */
        volatile boolean dropped;

        ExecutionSubscriberHandle(ExecutionSubscriber sink, long startingCursor) {
            this.sink = sink;
            this.lastDeliveredSequence = startingCursor;
        }

        boolean isOpen() {
            return !dropped && sink.isOpen();
        }
    }

    // =================================================================
    // Subscribe / unsubscribe
    // =================================================================

    /**
     * §4 ALGORITHM STEP 1: authorize (the caller/controller's §4
     * ownership chain + @PreAuthorize) — this method ONLY registers the
     * sink. The starting cursor is the caller's read high-water (the
     * snapshot's latestStreamSequence): committed frames ABOVE it replay;
     * frames ≤ it are already visible in the snapshot the client fetched.
     */
    public void subscribe(UUID executionId, ExecutionSubscriber subscriber) {
        subscribe(executionId, subscriber, 0L);
    }

    /** Overload letting the controller set an explicit starting cursor. */
    public void subscribe(UUID executionId, ExecutionSubscriber subscriber, long startAfterSequence) {
        ExecutionSubscriberHandle handle =
                new ExecutionSubscriberHandle(subscriber, startAfterSequence);
        subscribers.computeIfAbsent(executionId, k -> new CopyOnWriteArraySet<>())
                .add(handle);
        log.debug("Execution subscriber {} attached to execution {} (from cursor {})",
                subscriber.id(), executionId, startAfterSequence);
    }

    public void unsubscribe(UUID executionId, ExecutionSubscriber subscriber) {
        Set<ExecutionSubscriberHandle> set = subscribers.get(executionId);
        if (set != null) {
            set.removeIf(handle -> handle.sink == subscriber);
            if (set.isEmpty()) {
                subscribers.remove(executionId, set);
            }
        }
    }

    /** Live local subscriber count (test + debug helper). */
    public int subscriberCount(UUID executionId) {
        Set<ExecutionSubscriberHandle> set = subscribers.get(executionId);
        return set == null ? 0 : set.size();
    }

    // =================================================================
    // §4 ALGORITHM (test-facing): replay + drain + live seam
    // =================================================================

    /**
     * §4 ALGORITHM STEPS 3–5: read the durable high-water + replay rows,
     * send them ordered, then drain. (Subscribe/buffer are steps 1–2 —
     * done above.) A cursor below the retention watermark emits the gap
     * + a FRESH snapshot and never claims complete replay.
     *
     * @return the replay-gap info map when a gap was emitted, else null
     */
    public Map<String, Object> replayWithGapCheck(UUID executionId,
                                                  ExecutionSubscriber subscriber,
                                                  long afterSequence) {
        return replayAndDrain(executionId, subscriber, afterSequence);
    }

    /**
     * §4 ALGORITHM STEPS 3–5: read the durable high-water + replay rows,
     * send them ordered, then drain. (Subscribe/buffer are steps 1–2 —
     * done above.) A cursor below the retention watermark emits the gap
     * + a FRESH snapshot and never claims complete replay.
     *
     * @return the replay-gap info map when a gap was emitted, else null
     */
    public Map<String, Object> replayAndDrain(UUID executionId,
                                              ExecutionSubscriber subscriber,
                                              long afterSequence) {
        ExecutionSubscriberHandle handle = findHandle(executionId, subscriber);
        if (handle == null) {
            // A subscriber not in the set (already dropped) — nothing to do.
            return null;
        }
        synchronized (handle.sendLock) {
            return replayLocked(executionId, handle, afterSequence);
        }
    }

    /** The gap check + ordered replay, under the handle lock. */
    private Map<String, Object> replayLocked(UUID executionId,
                                             ExecutionSubscriberHandle handle,
                                             long afterSequence) {
        long earliest = earliestRetained(executionId);
        if (afterSequence < 0) {
            afterSequence = 0;
        }
        if (afterSequence > 0 && earliest > 0 && afterSequence < earliest) {
            // §4: a cursor below retention → the gap event + fresh snapshot.
            Map<String, Object> gap = new LinkedHashMap<>();
            gap.put("event", ExecutionViewService.REPLAY_GAP_EVENT);
            gap.put("earliestAvailableStreamSequence", earliest);
            ExecutionViewService.ExecutionView fresh = null;
            try {
                fresh = viewService.viewOf(executionExecution(executionId));
            } catch (Exception readFailure) {
                log.debug("Replay-gap snapshot for execution {} unavailable: {}",
                        executionId, readFailure.getMessage());
            }
            if (fresh != null) {
                gap.put("snapshot", snapshotMap(fresh));
            }
            try {
                if (!handle.isOpen()) {
                    dropHandle(executionId, handle, "closed at gap");
                    return gap;
                }
                handle.sink.send(gapFrame(
                        ExecutionViewService.REPLAY_GAP_EVENT, gap));
            } catch (IOException e) {
                dropHandle(executionId, handle,
                        "gap-frame send failure: " + e.getMessage());
                return gap;
            }
            // The cursor jumps to the CURRENT high-water — the client
            // re-fetches history through GET /events (never claimed a
            // complete replay).
            handle.lastDeliveredSequence = viewService.currentStreamSequence(executionId);
            return gap;
        }
        // Normal replay: the durable rows above afterSequence (cursor order).
        try {
            List<ExecutionEvent> rows = eventRepository
                    .findByExecutionIdAndStreamSequenceGreaterThanOrderByStreamSequenceAsc(
                            executionId, afterSequence);
            for (ExecutionEvent row : rows) {
                if (!sendDurable(executionId, handle, row)) {
                    return null;
                }
            }
        } catch (RuntimeException e) {
            log.debug("Execution replay read for {} failed: {}", executionId, e.getMessage());
        }
        return null;
    }

    private ExecutionSubscriberHandle findHandle(UUID executionId,
                                                 ExecutionSubscriber subscriber) {
        Set<ExecutionSubscriberHandle> set = subscribers.get(executionId);
        if (set == null) {
            return null;
        }
        for (ExecutionSubscriberHandle handle : set) {
            if (handle.sink == subscriber) {
                return handle;
            }
        }
        return null;
    }

    /** §3.5 read: the earliest retained cursor (0 when nothing survives). */
    private long earliestRetained(UUID executionId) {
        return viewService.earliestAvailableStreamSequence(executionId);
    }

    /**
     * The execution row for the gap-time fresh snapshot (nullable when the
     * row vanished mid-replay — the gap frame still carries the watermark).
     */
    private ai.myrmec.engine.inference.execution.SessionExecution executionExecution(
            UUID executionId) {
        return executionRepository.findById(executionId).orElse(null);
    }

    /** The §4 snapshot map (the fresh-snapshot-on-gap frame). */
    private Map<String, Object> snapshotMap(ExecutionViewService.ExecutionView view) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("executionId", view.executionId().toString());
        map.put("sessionId", view.sessionId().toString());
        map.put("state", view.state());
        map.put("holdState", view.holdState());
        map.put("controlRevision", view.controlRevision());
        map.put("acceptedControlRevision", view.acceptedControlRevision());
        map.put("controlStateSequence", view.controlStateSequence());
        map.put("idleResumeAt", iso(view.idleResumeAt()));
        map.put("deadline", iso(view.deadline()));
        map.put("pendingInteractionId", view.pendingInteractionId() == null
                ? null : view.pendingInteractionId().toString());
        map.put("latestStreamSequence", view.latestStreamSequence());
        map.put("earliestAvailableStreamSequence", view.earliestAvailableStreamSequence());
        map.put("usageStatus", view.usageStatus());
        map.put("interactionUsage", view.interactionUsage());
        map.put("interactionPolicy", view.interactionPolicy());
        return map;
    }

    private static String iso(java.time.Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private String gapFrame(String eventName, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("name", eventName);
        envelope.put("payload", payload);
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            return "{}";
        }
    }

    // =================================================================
    // Fan-out arms (wakeup hint + catch-up + live delivery)
    // =================================================================

    /**
     * §3.5 fan-out input: a durable event COMMITTED for the execution
     * (after-commit hint or relayed wakeup). The WAKEUP performs an
     * immediate cursor-ordered fetch per live subscriber — committed rows
     * above that subscriber's last delivered sequence, in order. The
     * DEDUPLICATION is the cursor comparison itself: the same cursor can
     * never deliver twice, and out-of-order wakeups collapse into one
     * ordered sweep. Wakeup failures self-heal through the periodic
     * catch-up sweep below.
     */
    public void notifyCommitted(UUID executionId, long sequence) {
        Set<ExecutionSubscriberHandle> set = subscribers.get(executionId);
        if (set == null || set.isEmpty()) {
            return;
        }
        try {
            catchUpAll(executionId);
        } catch (RuntimeException e) {
            // The ordered fetch must never propagate into the publisher
            // transaction path — the periodic sweep repairs the miss.
            log.debug("Execution stream wakeup for {} failed: {}",
                    executionId, e.getMessage());
        }
    }

    /**
     * §3.5 CATCH-UP (also the scheduled repair): read committed rows in
     * cursor order above each subscriber's last delivered sequence and
     * deliver. Idempotent — safe to invoke from the relay handler, the
     * after-commit fan-out, and the scheduler.
     */
    public void catchUp(UUID executionId, ExecutionSubscriber subscriber) {
        ExecutionSubscriberHandle handle = findHandle(executionId, subscriber);
        if (handle == null) {
            return;
        }
        synchronized (handle.sendLock) {
            catchUpHandle(executionId, handle);
        }
    }

    /** The cursor-ordered catch-up for one handle (lock held). */
    private void catchUpHandle(UUID executionId, ExecutionSubscriberHandle handle) {
        if (!handle.isOpen()) {
            dropHandle(executionId, handle, "closed before catch-up");
            return;
        }
        long from = handle.lastDeliveredSequence;
        List<ExecutionEvent> rows = eventRepository
                .findByExecutionIdAndStreamSequenceGreaterThanOrderByStreamSequenceAsc(
                        executionId, from);
        int sent = 0;
        for (ExecutionEvent row : rows) {
            if (row.getStreamSequence() == null) {
                continue;   // durable rows only
            }
            if (!sendDurable(executionId, handle, row)) {
                return;   // the subscriber is gone; stop the ordered sweep
            }
            sent++;
            if (sent >= catchUpBatch) {
                break;
            }
        }
        maybeCompleteTerminal(executionId, handle);
    }

    /**
     * §4 terminal settle: when the execution is TERMINAL (terminalMessageId
     * set or state COMPLETED/FAILED/PAUSED/CANCELLED/REJECTED) and every
     * committed cursor is already delivered ({@code lastDeliveredSequence
     * >= streamSequence}), the subscriber has seen the whole story —
     * complete its transport (the SSE emitter ends cleanly; the HTTP
     * response closes; the client's Last-Event-ID replay still works) and
     * drop the handle with a terminal reason. The §4 bounded-settlement
     * guarantee: closure waits only for this bounded sweep window, never
     * a live-event race.
     */
    private void maybeCompleteTerminal(UUID executionId,
                                       ExecutionSubscriberHandle handle) {
        if (handle.dropped || !handle.isOpen()) {
            return;
        }
        ai.myrmec.engine.inference.execution.SessionExecution execution =
                executionRepository.findById(executionId).orElse(null);
        if (execution == null || !isTerminalExecution(execution)) {
            return;
        }
        long committedCursor = execution.getStreamSequence() == null
                ? 0L : execution.getStreamSequence();
        if (handle.lastDeliveredSequence < committedCursor) {
            return;   // rows still owed — the next sweep continues delivery
        }
        try {
            handle.sink.close();
        } catch (RuntimeException e) {
            log.debug("Execution stream terminal close for execution {} subscriber {} "
                    + "failed: {}", executionId, handle.sink.id(), e.getMessage());
        }
        dropHandle(executionId, handle,
                "execution terminal + fully delivered (§4 settle)");
    }

    /** §4 terminal = terminalMessageId recorded or a closed-lifecycle state. */
    private static boolean isTerminalExecution(
            ai.myrmec.engine.inference.execution.SessionExecution execution) {
        return execution.getTerminalMessageId() != null
                || switch (execution.getState()) {
            case COMPLETED, FAILED, PAUSED, CANCELLED, REJECTED -> true;
            default -> false;
        };
    }

    /**
     * §3.5 REPAIR sweep — the DEDICATED scheduler's pass. A periodic pass
     * over every execution that has live local subscribers; each handle's
     * durable catch-up runs from its OWN last delivered sequence, so a
     * lost after-commit/relay wakeup is repaired here (commit-before-
     * publication crashes, relay hints dropped, out-of-order node
     * notifications). Also the §4 TERMINAL-CLOSURE enforcement: a
     * handle fully delivered past a terminal execution is completed and
     * dropped with a terminal reason. Never a per-client thread.
     *
     * <p>NOT {@code @Scheduled}: the two stream sweeps are manually
     * scheduled on the dedicated {@code executionStreamScheduler}
     * ({@link #startSweeps}) so a stalled SSE send wedges at most THIS
     * broker's sweeps — never the engine's other scheduled beans.</p>
     * Failures are logged and repaired by the next pass.
     */
    public void scheduledCatchUpSweep() {
        try {
            for (Map.Entry<UUID, Set<ExecutionSubscriberHandle>> entry
                    : subscribers.entrySet()) {
                catchUpAll(entry.getKey());
            }
        } catch (RuntimeException e) {
            log.debug("Execution stream scheduled catch-up failed: {}", e.getMessage());
        }
    }

    /**
     * §4 heartbeat sweep — the SAME dedicated scheduler pass pushes a
     * heartbeat frame to every connected subscriber (no per-client timer;
     * §4: "Connected/heartbeat have no replay cursor"). A send failure
     * drops the dead sink.
     */
    public void scheduledHeartbeatSweep() {
        long now = System.currentTimeMillis();
        String frame = heartbeatFrame(now);
        for (Map.Entry<UUID, Set<ExecutionSubscriberHandle>> entry
                : subscribers.entrySet()) {
            for (ExecutionSubscriberHandle handle : entry.getValue()) {
                if (handle.dropped || !handle.isOpen()) {
                    continue;
                }
                try {
                    synchronized (handle.sendLock) {
                        handle.sink.send(frame);
                    }
                } catch (IOException e) {
                    dropHandle(entry.getKey(), handle,
                            "heartbeat send failure: " + e.getMessage());
                }
            }
        }
    }

    /** The id-less heartbeat JSON frame (§4: no replay cursor). */
    private String heartbeatFrame(long nowMs) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("name", "heartbeat");
        envelope.put("sentAt", java.time.Instant.ofEpochMilli(nowMs).toString());
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** One catch-up sweep: every live handle of this execution. */
    private void catchUpAll(UUID executionId) {
        Set<ExecutionSubscriberHandle> set = subscribers.get(executionId);
        if (set == null || set.isEmpty()) {
            return;
        }
        for (ExecutionSubscriberHandle handle : set) {
            if (handle.dropped) {
                continue;
            }
            synchronized (handle.sendLock) {
                catchUpHandle(executionId, handle);
            }
        }
    }

    /**
     * Durable frame delivery: re-READ the row's data (sanitized) and send
     * it as the cursor-keyed frame. Returns false when the subscriber is
     * gone (the catch-up sweep stops, no partial-order damage).
     */
    private boolean sendDurable(UUID executionId, ExecutionSubscriberHandle handle,
                                ExecutionEvent row) {
        if (!handle.isOpen()) {
            dropHandle(executionId, handle, "closed");
            return false;
        }
        if (row.getStreamSequence() == null
                || row.getStreamSequence() <= handle.lastDeliveredSequence) {
            // The cursor dedup: already delivered, or a wakeup for an
            // older frame — NEVER deliver out of order.
            return true;
        }
        ExecutionStreamEvent event = viewService.sanitize(row);
        try {
            synchronized (handle.sendLock) {
                handle.sink.send(event.toJson(objectMapper));
            }
            handle.lastDeliveredSequence = row.getStreamSequence();
            return true;
        } catch (IOException e) {
            dropHandle(executionId, handle,
                    "send failure: " + e.getMessage());
            return false;
        }
    }

    private void dropHandle(UUID executionId, ExecutionSubscriberHandle handle,
                            String reason) {
        handle.dropped = true;
        Set<ExecutionSubscriberHandle> set = subscribers.get(executionId);
        if (set != null) {
            set.remove(handle);
            if (set.isEmpty()) {
                subscribers.remove(executionId, set);
            }
        }
        log.debug("Execution subscriber {} disconnected from execution {} ({}) "
                + "— cursor replay available at its Last-Event-ID",
                handle.sink.id(), executionId, reason);
    }

    // =================================================================
    // Multi-node relay (the conversation-broker fan-out pattern)
    // =================================================================

    /** Register the transport's local delivery callback (PostConstruct). */
    @PostConstruct
    void wireRemoteHandler() {
        nodeTransport.setExecutionFanoutHandler((request, frame) ->
                deliverRemoteFrame(request::frameJson));
    }

    /**
     * The NodeTransport execution arm's inbound relay: a peer's typed
     * execution-stream frame ({@code StreamRelayRequest} with a null
     * conversationId + the execution envelope JSON). Delivers EPHMERAL
     * frames (deltas) verbatim; durable frames ride the CATCH-UP (the
     * committed row's bytes win).
     */
    public void deliverRemoteFrame(StreamRelayRequestHolder request) {
        ExecutionStreamEventEnvelope parsed = ExecutionStreamEventEnvelope.parse(request.frameJson());
        if (parsed == null || parsed.executionId() == null) {
            log.debug("Ignoring malformed execution relay frame");
            return;
        }
        if (parsed.ephemeral()) {
            Set<ExecutionSubscriberHandle> set = subscribers.get(parsed.executionId());
            if (set == null) {
                return;
            }
            for (ExecutionSubscriberHandle handle : set) {
                if (!handle.isOpen() || handle.dropped) {
                    continue;
                }
                try {
                    synchronized (handle.sendLock) {
                        handle.sink.send(request.frameJson());
                    }
                } catch (IOException e) {
                    dropHandle(parsed.executionId(), handle,
                            "delta send failure: " + e.getMessage());
                }
            }
            return;
        }
        // A durable frame from the peer: the wakeup hint only.
        notifyCommitted(parsed.executionId(), parsed.streamSequence() == null
                ? 0 : parsed.streamSequence());
    }

    /**
     * The {@code StreamRelayController}'s entry — route via the typed
     * envelope parse (the inbound execution arm).
     */
    public void deliverRemoteFrameRelay(
            ai.myrmec.engine.node.StreamRelayRequest request) {
        deliverRemoteFrame(request::frameJson);
    }

    // =================================================================
    // The typed relay-request view (unwraps the node envelope)
    // =================================================================

    /** Functional view over the relayed frame's JSON bytes. */
    public interface StreamRelayRequestHolder {
        String frameJson();
    }

    /** The parsed relay envelope ({@code {name, payload, ...}}). */
    public record ExecutionStreamEventEnvelope(String name, Long streamSequence,
                                               UUID executionId, boolean ephemeral) {

        static ExecutionStreamEventEnvelope parse(String jsonFrame) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> envelope = mapper.readValue(jsonFrame,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
                String name = envelope.get("name") instanceof String s ? s : null;
                Long sequence = envelope.get("streamSequence") instanceof Number n
                        ? n.longValue() : null;
                String executionIdRaw = envelope.get("executionId") instanceof String s ? s : null;
                UUID executionId = executionIdRaw == null ? null : UUID.fromString(executionIdRaw);
                boolean ephemeral = sequence == null;
                return new ExecutionStreamEventEnvelope(name, sequence, executionId,
                        ephemeral);
            } catch (Exception e) {
                return null;
            }
        }
    }
}
