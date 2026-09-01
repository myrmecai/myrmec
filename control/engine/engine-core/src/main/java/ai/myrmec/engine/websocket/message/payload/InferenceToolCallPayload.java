// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.message.payload;

import java.util.Map;
import java.util.UUID;

/**
 * Payload for the {@code inference.tool_call} frame (§5.5).
 * Agent → Engine. Agent's model requested a tool.
 */
public record InferenceToolCallPayload(
        UUID requestId,
        UUID sessionId,
        String toolCallId,
        String name,
        Map<String, Object> args) {}