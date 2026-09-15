// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.workflow;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Parks an assembled workflow dispatch between {@code session.offer} and the
 * host's {@code session.opened} acknowledgement (protocol &sect;7.1&ndash;&sect;7.4),
 * mirroring {@code PendingConversationTurns} for the scheduled dispatcher.
 *
 * <p>{@link TaskDispatcherService} cannot ship {@code execution.start} when it
 * offers a session: &sect;7.4 requires the session to be allocation-ACTIVE
 * first, which only happens when the host answers {@code session.accept}
 * (OFFERED&nbsp;&rarr;&nbsp;INITIALIZING) and then {@code session.opened}
 * (INITIALIZING&nbsp;&rarr;&nbsp;ACTIVE). Those frames arrive on the
 * host-control socket on a different thread, <em>after</em> the dispatcher pass
 * has returned. This registry is the handoff: the dispatcher stages the task
 * keyed by session id, and {@code HostControlWebSocketHandler} resumes it once
 * the session is ACTIVE.</p>
 *
 * <p><b>Bounded by design.</b> An entry is consumed on {@code session.opened},
 * dropped on {@code session.reject}/{@code session.closed}, or evicted once its
 * TTL lapses (the allocator's offer-expiry sweep closes the reservation at the
 * same horizon, so a lapsed entry can never be resumed). State is node-local
 * exactly like the allocation it follows: the offer only ever goes to the host
 * the allocator pinned, so the accept/opened answers return to this replica.</p>
 */
@Slf4j
@Component
public class PendingTaskDispatches {

    /**
     * How long a staged dispatch stays resumable. Matches the allocator's
     * default offer timeout ({@code myrmec.host.offer-timeout-seconds}), after
     * which the session is swept to CLOSED and the task must be re-dispatched.
     */
    static final Duration TTL = Duration.ofSeconds(60);

    /** Backstop cap; the oldest entry is dropped when exceeded. */
    static final int MAX_ENTRIES = 1000;

    /** One parked dispatch plus the instant it was staged (the TTL clock). */
    public record PendingDispatch(TaskDispatchContext context, Instant stagedAt) {}

    private final Map<UUID, PendingDispatch> pending = new ConcurrentHashMap<>();

    /** Park a dispatch for {@code sessionId} until the host confirms it opened. */
    public void stage(UUID sessionId, TaskDispatchContext context) {
        evictStale();
        if (pending.size() >= MAX_ENTRIES) {
            pending.keySet().stream().findFirst().ifPresent(oldest -> {
                pending.remove(oldest);
                log.warn("PendingTaskDispatches at capacity — dropped staged dispatch for session {}",
                        oldest);
            });
        }
        pending.put(sessionId, new PendingDispatch(context, Instant.now()));
        log.debug("Staged workflow dispatch for session {} (task {})",
                sessionId, context.taskId());
    }

    /** Consume the parked dispatch for a session that just became ACTIVE. */
    public Optional<PendingDispatch> take(UUID sessionId) {
        PendingDispatch dispatch = pending.remove(sessionId);
        if (dispatch != null && isExpired(dispatch)) {
            log.warn("Discarding expired staged dispatch for session {} (task {})",
                    sessionId, dispatch.context().taskId());
            return Optional.empty();
        }
        return Optional.ofNullable(dispatch);
    }

    /**
     * Read the parked dispatch without consuming it &mdash; used on
     * {@code session.accept}, where the context still has to survive until
     * {@code session.opened} ships the execution (&sect;7.4).
     */
    public TaskDispatchContext peek(UUID sessionId) {
        PendingDispatch dispatch = pending.get(sessionId);
        return dispatch == null || isExpired(dispatch) ? null : dispatch.context();
    }

    /** Drop a parked dispatch without shipping it (reject / close / failure). */
    public void discard(UUID sessionId) {
        if (pending.remove(sessionId) != null) {
            log.debug("Discarded staged dispatch for session {}", sessionId);
        }
    }

    /** Number of parked dispatches. Exposed for tests. */
    public int size() {
        return pending.size();
    }

    private void evictStale() {
        pending.entrySet().removeIf(e -> isExpired(e.getValue()));
    }

    private static boolean isExpired(PendingDispatch dispatch) {
        return dispatch.stagedAt().plus(TTL).isBefore(Instant.now());
    }
}
