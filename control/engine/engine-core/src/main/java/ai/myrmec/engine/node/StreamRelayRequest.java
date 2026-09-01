// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.node;

import java.util.UUID;

/**
 * Node-to-node stream relay envelope. Carries an opaque, already-serialized
 * streaming frame plus the conversation id. The receiving replica delivers
 * {@code frameJson} verbatim to its local SSE subscribers without ever
 * deserializing it into typed payloads.
 */
public record StreamRelayRequest(UUID conversationId, String frameJson) {
}