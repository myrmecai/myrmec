// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

import ai.myrmec.engine.websocket.host.HostProtocol;

import java.util.List;
import java.util.UUID;

/**
 * host.resume (§13): a reconnected host reports the sessions it retained
 * across the disconnect so the engine can answer with authoritative
 * keep/cancel/close decisions. All fields are §13-verbatim.
 *
 * @param previousHostInstanceId the instance the host last served under
 * @param instanceNonce          the supervisor-run identity of that instance
 * @param sessions               host-side session accounting (may be empty)
 */
public record HostResumePayload(
        UUID previousHostInstanceId,
        UUID instanceNonce,
        List<RetainedSession> sessions) {

    /**
     * §13 retained-session summary: what the host held locally at the
     * drop. {@code capacityHeld} is the reconciliation assertion — the
     * engine may return KEEP only when it is true (§13 slot-ownership
     * rule). {@code lastSentSequence} is the host's durable-event cursor;
     * {@code lastAcknowledgedMessageId} the last terminal/session message
     * the engine acknowledged. Execution accounting is deliberately loose
     * on the wire (§13 example carries only {@code activeExecutionId}) —
     * the engine's own execution rows are the durable authority for
     * terminal dedup, so no per-execution messageIds are required here.
     */
    public record RetainedSession(
            UUID sessionId,
            String state,
            boolean capacityHeld,
            UUID activeExecutionId,
            Long lastSentSequence,
            String lastAcknowledgedMessageId) {
    }

    /** Jackson convenience: tolerate an absent sessions array. */
    public HostResumePayload {
        sessions = sessions == null ? List.of() : sessions;
    }

    /** The engine's authoritative answer (§13 host.reconcile). */
    public record ReconcilePayload(
            UUID hostInstanceId,
            List<Decision> decisions) {

        /**
         * One per-session decision. KEEP carries {@code resumeFromSequence}
         * (the replay cursor: resend durable events after this envelope
         * sequence); CANCEL_EXECUTION carries {@code executionId} (it IS
         * the cancellation command, §13); CLOSE carries {@code reasonCode}.
         */
        public record Decision(
                UUID sessionId,
                String action,
                Long resumeFromSequence,
                UUID executionId,
                String reasonCode) {

            public static Decision keep(UUID sessionId, long resumeFromSequence) {
                return new Decision(sessionId, HostProtocol.RECONCILE_KEEP,
                        resumeFromSequence, null, null);
            }

            public static Decision cancelExecution(UUID sessionId, UUID executionId) {
                return new Decision(sessionId, HostProtocol.RECONCILE_CANCEL_EXECUTION,
                        null, executionId, null);
            }

            public static Decision close(UUID sessionId, String reasonCode) {
                return new Decision(sessionId, HostProtocol.RECONCILE_CLOSE,
                        null, null, reasonCode);
            }
        }
    }
}