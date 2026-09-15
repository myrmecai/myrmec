// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.conversation.dispatch;

import ai.myrmec.engine.websocket.host.payload.ExecutionStartPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Parks an assembled conversation turn between {@code session.offer} and the
 * host's {@code session.opened} acknowledgement (protocol &sect;7.1&ndash;&sect;7.4).
 *
 * <p>The dispatcher cannot ship {@code execution.start} when it offers a session:
 * &sect;7.4 requires the session to be allocation-ACTIVE first, which only happens
 * when the host answers {@code session.accept} (OFFERED&nbsp;&rarr;&nbsp;INITIALIZING)
 * and then {@code session.opened} (INITIALIZING&nbsp;&rarr;&nbsp;ACTIVE). Those
 * frames arrive on the host-control socket on a different thread, <em>after</em>
 * {@code ConversationTurnDispatcher.dispatch()} has returned. This registry is the
 * handoff between the two: the dispatcher stages the turn keyed by session id, and
 * {@code HostControlWebSocketHandler} takes it once the session is ACTIVE.</p>
 *
 * <p><b>Why a map and not an event.</b> The handler needs the assembled payload
 * itself, not just a notification, and the continuation must be exactly-once: an
 * {@code @EventListener} fan-out would leave the payload nowhere to live and the
 * ordering (accept&nbsp;&rarr;&nbsp;open&nbsp;&rarr;&nbsp;opened&nbsp;&rarr;&nbsp;start)
 * implicit. A one-element take is the smallest thing that preserves both. State
 * is node-local, exactly like the allocation it follows: the offer is only ever
 * sent to the host the allocator pinned, so the accept/opened answers return to
 * this replica.</p>
 *
 * <p><b>Bounded by design.</b> An entry is consumed on {@code session.opened}, or
 * dropped on {@code session.reject}/{@code session.closed}, or evicted once its
 * TTL lapses (the allocator's offer-expiry sweep closes the session at the same
 * horizon, so a lapsed entry can never be resumed). The cap is a backstop against
 * a pathological producer; exceeding it drops the OLDEST entry, which fails the
 * same way an expired offer does — the next turn re-offers cleanly.</p>
 */
@Slf4j
@Component
public class PendingConversationTurns {

    /**
     * How long a staged turn stays resumable. Matches the allocator's default
     * offer timeout ({@code myrmec.host.offer-timeout-seconds}), after which the
     * session is swept to CLOSED and the turn must be re-offered anyway.
     */
    static final Duration TTL = Duration.ofSeconds(60);

    /** Backstop cap; the oldest entry is dropped when exceeded. */
    static final int MAX_ENTRIES = 1000;

    /**
     * One parked turn: the conversation it belongs to, the &sect;8.1 input block for
     * {@code ExecutionRegistry.start}, and the fully assembled wire payload (built
     * with a null {@code executionId}, since the registry mints that id only once the
     * session is ACTIVE).
     */
    public record PendingTurn(UUID conversationId, Map<String, Object> input,
                              ExecutionStartPayload wirePayload, Instant stagedAt) {

        /** Re-stamp the payload with the execution id the registry just minted. */
        public ExecutionStartPayload toStartPayload(UUID executionId) {
            return new ExecutionStartPayload(
                    executionId,
                    wirePayload.sessionId(),
                    wirePayload.sequenceNo(),
                    wirePayload.requestId(),
                    wirePayload.deadline(),
                    wirePayload.input(),
                    wirePayload.toolPolicy(),
                    wirePayload.output());
        }
    }

    private final Map<UUID, PendingTurn> pending = new ConcurrentHashMap<>();

    /** Park a turn for {@code sessionId} until the host confirms it opened. */
    public void stage(UUID sessionId, UUID conversationId, Map<String, Object> input,
                      ExecutionStartPayload wirePayload) {
        evictStale();
        if (pending.size() >= MAX_ENTRIES) {
            pending.keySet().stream().findFirst().ifPresent(oldest -> {
                pending.remove(oldest);
                log.warn("PendingConversationTurns at capacity — dropped staged turn for session {}", oldest);
            });
        }
        pending.put(sessionId, new PendingTurn(conversationId, input, wirePayload, Instant.now()));
        log.debug("Staged conversation turn for session {} (conv {})", sessionId, conversationId);
    }

    /** Consume the parked turn for a session that just became ACTIVE. */
    public Optional<PendingTurn> take(UUID sessionId) {
        PendingTurn turn = pending.remove(sessionId);
        if (turn != null && isExpired(turn)) {
            log.warn("Discarding expired staged turn for session {} (conv {})",
                    sessionId, turn.conversationId());
            return Optional.empty();
        }
        return Optional.ofNullable(turn);
    }

    /**
     * Read the parked turn without consuming it &mdash; used on
     * {@code session.accept}, where the turn still has to survive until
     * {@code session.opened} ships it (&sect;7.4).
     */
    public Optional<PendingTurn> peek(UUID sessionId) {
        PendingTurn turn = pending.get(sessionId);
        return turn == null || isExpired(turn) ? Optional.empty() : Optional.of(turn);
    }

    /** Drop a parked turn without shipping it (reject / close / failure). */
    public void discard(UUID sessionId) {
        if (pending.remove(sessionId) != null) {
            log.debug("Discarded staged turn for session {}", sessionId);
        }
    }

    /** Number of parked turns. Exposed for tests. */
    public int size() {
        return pending.size();
    }

    private void evictStale() {
        pending.entrySet().removeIf(e -> isExpired(e.getValue()));
    }

    private static boolean isExpired(PendingTurn turn) {
        return turn.stagedAt().plus(TTL).isBefore(Instant.now());
    }
}
