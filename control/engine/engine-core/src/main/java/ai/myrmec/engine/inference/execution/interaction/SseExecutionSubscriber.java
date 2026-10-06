// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@link ExecutionSubscriber} backed by an {@link SseEmitter} — the §4
 * {@code GET /stream} live sink.
 *
 * <p>SSE ids ride ONLY durable frames (the decimal stream_sequence;
 * §3.5); delta frames are sent id-less (ephemeral), and connected/
 * heartbeat frames likewise carry no id so a reconnecting client's
 * {@code Last-Event-ID} always names its last DURABLE frame.</p>
 */
@Slf4j
public class SseExecutionSubscriber implements ExecutionSubscriber {

    private final String id = java.util.UUID.randomUUID().toString();
    private final SseEmitter emitter;
    private volatile boolean open = true;

    public SseExecutionSubscriber(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    /**
     * Send one frame. The serialized JSON rides the {@code data} field;
     * the event name and (only for durable frames) the numeric id come
     * from the parsed envelope keys so the wire is exactly one send.
     */
    @Override
    public void send(String jsonFrame) throws java.io.IOException {
        SseEmitter.SseEventBuilder builder = SseEmitter.event();
        var event = ExecutionViewService.parseEnvelope(jsonFrame);
        if (event != null) {
            if (event.name() != null) {
                builder = builder.name(event.name());
            }
            if (event.sseId() != null) {
                builder = builder.id(event.sseId());
            }
        }
        builder = builder.data(jsonFrame == null ? "" : jsonFrame);
        try {
            emitter.send(builder);
        } catch (java.io.IOException e) {
            open = false;
            throw e;
        } catch (IllegalStateException e) {
            open = false;
            throw new java.io.IOException("SSE emitter is closed", e);
        }
    }

    /** Mark the sink dead once the emitter completed/timed out/errored. */
    public void markClosed() {
        open = false;
    }

    /**
     * §4 terminal settle: complete the emitter cleanly — the client sees
     * the stream END (not an error). A client reconnecting after closure
     * re-enters the Last-Event-ID replay, then hits the terminal-sweep
     * closure again (the §4 closure is idempotent at the sweep).
     */
    @Override
    public void close() {
        open = false;
        try {
            emitter.complete();
        } catch (RuntimeException e) {
            log.debug("Execution SSE emitter already completed: {}", e.getMessage());
        }
    }
}
