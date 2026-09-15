// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference;

import java.util.List;
import java.util.Map;

/**
 * A single message in the assembled transcript (Â§5.3).
 * The engine fully assembles this; the agent maps it straight to LangChain.
 */
public record InferenceMessage(
        String role,            // "system", "user", "assistant", "tool"
        String content,         // nullable for assistant messages with only tool calls
        List<ContentPart> parts,  // nullable â€” multimodal content parts (Â§5.4)
        List<ToolCall> toolCalls) {  // nullable â€” only for assistant messages

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
