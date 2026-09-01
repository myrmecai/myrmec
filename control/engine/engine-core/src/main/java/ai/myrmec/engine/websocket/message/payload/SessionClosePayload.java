// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.message.payload;

import java.util.UUID;

/**
 * Payload for the {@code session.close} frame (§5.1).
 * Engine → Agent. Tear down a session (conversation unbind / execution end).
 */
public record SessionClosePayload(
        UUID sessionId) {}