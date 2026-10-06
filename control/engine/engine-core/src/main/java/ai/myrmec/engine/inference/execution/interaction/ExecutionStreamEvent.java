// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Task 9 (plan 2026-10-03-session-interaction, §4): one SSE envelope on the
 * execution stream.
 *
 * <p>Durable frames carry {@code streamSequence} — the decimal public
 * cursor (SSE {@code id}), allocated under the execution row lock (§3.5).
 * Deltas (ephemeral {@code execution.interaction.delta}) carry NO id and
 * NEVER ride the durable cursor; connected/heartbeat carry neither.</p>
 *
 * @param streamSequence the decimal public stream cursor; null for
 *                       ephemeral deltas and connection frames
 * @param name           the §4 SSE event name
 * @param payload        the already-sanitized public payload map
 */
public record ExecutionStreamEvent(Long streamSequence, String name,
                                   Map<String, Object> payload) {

    /** The §4 ephemeral delta event name (no SSE id). */
    public static final String DELTA_NAME = "execution.interaction.delta";

    /** Connected/heartbeat: no replay cursor. */
    public static ExecutionStreamEvent connected(Map<String, Object> payload) {
        return new ExecutionStreamEvent(null, "connected", payload);
    }

    /** Ephemeral delta: no id — replay never reproduces it. */
    public static ExecutionStreamEvent delta(UUID interactionId, int index, String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("interactionId", interactionId == null ? null : interactionId.toString());
        payload.put("index", index);
        payload.put("text", text);
        return new ExecutionStreamEvent(null, DELTA_NAME, payload);
    }

    /** §4 decimal SSE id — the durable cursor as a plain integer string. */
    public String sseId() {
        return streamSequence == null ? null : String.valueOf(streamSequence);
    }

    public boolean isEphemeral() {
        return streamSequence == null;
    }

    public boolean isConnected() {
        return "connected".equals(name) || "heartbeat".equals(name);
    }

    /** JSON-encode the payload via the given mapper (never throws). */
    public String toJson(ObjectMapper mapper) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("streamSequence", streamSequence);
            envelope.put("name", name);
            envelope.put("payload", payload);
            return mapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    /** Compact JSON helper shared with the node-relay tests. */
    public static final class Json {
        private Json() {
        }

        public static String encode(Map<String, Object> payload) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                return mapper.writeValueAsString(payload);
            } catch (JsonProcessingException e) {
                return "{}";
            }
        }
    }
}