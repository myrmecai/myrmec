// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.node;

import java.util.UUID;

/**
 * Node-to-node stream relay envelope. Carries an opaque, already-serialized
 * streaming frame plus the conversation id. The receiving replica delivers
 * {@code frameJson} verbatim to its local SSE subscribers without ever
 * deserializing it into typed payloads.
 *
 * <p><b>Task 9 execution arm</b> (plan 2026-10-03-session-interaction):
 * {@code conversationId == null} marks the EXECUTION-stream arm — the
 * frame is an execution stream envelope
 * ({@code {executionId, streamSequence, name, payload}}) for the
 * execution-keyed broker. The conversation arm keeps a non-null id, so the
 * receiving side routes by a simple null check (no new endpoint, no new
 * secret handshake).</p>
 */
public record StreamRelayRequest(UUID conversationId, String frameJson) {

    /** True when this envelope targets the execution stream broker. */
    public boolean isExecutionArm() {
        return conversationId == null;
    }
}