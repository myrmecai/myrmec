// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

/**
 * Transport-agnostic sink for execution stream frames (the §4 subscriber
 * interface — the conversation stream broker pattern reused for the
 * execution-keyed §4 broker WITHOUT a conversation row).
 *
 * <p>The live client transport is Server-Sent Events
 * ({@link SseExecutionSubscriber}); test sinks implement the same three
 * methods so the broker stays transport-neutral.</p>
 */
public interface ExecutionSubscriber {

    /** Stable identifier for log lines and equality within the broker's set. */
    String id();

    /**
     * Whether this sink can still accept frames. Closed sinks are dropped.
     */
    boolean isOpen();

    /**
     * Deliver one pre-serialized JSON frame to the client. Must be safe to
     * call while the broker holds its per-subscriber lock. A thrown
     * {@link java.io.IOException} tells the broker to drop this subscriber.
     */
    void send(String jsonFrame) throws java.io.IOException;

    /**
     * §4 terminal settle: the execution is terminal AND fully delivered —
     * finish the transport cleanly (the SSE emitter completes, the HTTP
     * response ends). Called exactly once, by the broker, right before
     * (or right after — see the handle state) the broker drops the handle.
     */
    void close();
}
