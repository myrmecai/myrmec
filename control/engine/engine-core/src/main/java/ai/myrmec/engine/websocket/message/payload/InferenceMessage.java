// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.message.payload;

import java.util.List;
import java.util.Map;

/**
 * A single message in the assembled transcript (§5.3).
 * The engine fully assembles this; the agent maps it straight to LangChain.
 */
public record InferenceMessage(
        String role,            // "system", "user", "assistant", "tool"
        String content,         // nullable for assistant messages with only tool calls
        List<ContentPart> parts,  // nullable — multimodal content parts (§5.4)
        List<ToolCall> toolCalls) {  // nullable — only for assistant messages

    public record ContentPart(
            String type,         // "text" or "image"
            String text,         // present when type="text"
            String attachmentId, // present when type="image"
            String mediaType,    // present when type="image"
            String readContentPath) {}  // present when type="image"

    public record ToolCall(
            String id,
            String name,
            Map<String, Object> args) {}
}