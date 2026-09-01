// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.message.payload;

import java.util.UUID;

/**
 * Payload for the {@code inference.tool_result} frame (§5.5).
 * Agent → Engine. Result of a tool execution.
 */
public record InferenceToolResultPayload(
        UUID requestId,
        UUID sessionId,
        String toolCallId,
        String result,
        boolean isError) {}