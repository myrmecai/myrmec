// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference;

import ai.myrmec.engine.websocket.message.payload.InferenceMessage;
import ai.myrmec.engine.inference.InferenceRequestSpec.AttachmentDescriptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Conversation transcript composer (§6.3).
 *
 * <p>Ports {@code assembleConversationMessages()} from
 * {@code agents/src/executor/ConversationDispatcher.ts} byte-for-byte:
 * <ul>
 *   <li>system message = (conversation.systemPromptOverride ?? profile.systemPrompt) + pinnedFacts</li>
 *   <li>sliding-window history</li>
 *   <li>user message + attachment context</li>
 * </ul></p>
 */
@Slf4j
@Component
public class ConversationTranscriptComposer implements TranscriptComposer {

    @Override
    public List<InferenceMessage> compose(InferenceRequestSpec spec) {
        List<InferenceMessage> messages = new ArrayList<>();

        // System message: systemPrompt + pinnedFacts joined by \n\n, skip blanks
        String systemPrompt = spec.getConversationSystemPrompt();
        String pinnedFacts = spec.getPinnedFacts();
        StringBuilder system = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            system.append(systemPrompt);
        }
        if (pinnedFacts != null && !pinnedFacts.isBlank()) {
            if (system.length() > 0) system.append("\n\n");
            system.append(pinnedFacts);
        }
        if (system.length() > 0) {
            messages.add(new InferenceMessage("system", system.toString(), null, null));
        }

        // History entries
        if (spec.getHistory() != null) {
            for (InferenceRequestSpec.HistoryEntry entry : spec.getHistory()) {
                messages.add(new InferenceMessage(
                        normalizeRole(entry.role()),
                        entry.content(),
                        null,
                        null));
            }
        }

        // User message + attachment context
        String userMessage = spec.getUserMessage() != null ? spec.getUserMessage() : "";
        String attachmentContext = buildAttachmentContext(spec.getAttachments());
        String userContent = attachmentContext != null && !attachmentContext.isEmpty()
                ? userMessage + "\n\n" + attachmentContext
                : userMessage;
        if (!userContent.isEmpty()) {
            // Build parts for multimodal (image attachments)
            List<InferenceMessage.ContentPart> parts = buildImageParts(spec.getAttachments());
            messages.add(new InferenceMessage("user", userContent, parts, null));
        }

        return messages;
    }

    /**
     * Render bound attachments into a text block appended to the user message.
     * Port of {@code buildAttachmentContext} from ConversationDispatcher.ts.
     */
    private String buildAttachmentContext(List<AttachmentDescriptor> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return "";
        }
        List<String> blocks = new ArrayList<>();
        for (AttachmentDescriptor a : attachments) {
            if (a.inlineText() != null && !a.inlineText().isEmpty()) {
                blocks.add("--- Attached file: " + a.filename() + " (" + a.mediaType() + ") ---\n" + a.inlineText());
            } else if (a.image()) {
                blocks.add("--- Attached image: " + a.filename() + " (" + a.mediaType() + ", " + a.sizeBytes() + " bytes) ---");
            } else {
                blocks.add("--- Attached file: " + a.filename() + " (" + a.mediaType() + ", " + a.sizeBytes()
                        + " bytes; content not inlined, fetch by id " + a.id() + " if needed) ---");
            }
        }
        return String.join("\n\n", blocks);
    }

    /**
     * Build multimodal content parts for image attachments (§5.4).
     * The agent fetches the actual bytes on demand via readContentPath.
     */
    private List<InferenceMessage.ContentPart> buildImageParts(List<AttachmentDescriptor> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return null;
        }
        List<InferenceMessage.ContentPart> parts = new ArrayList<>();
        // Text part for the user message content
        for (AttachmentDescriptor a : attachments) {
            if (a.image() && a.readContentPath() != null) {
                parts.add(new InferenceMessage.ContentPart(
                        "image",
                        null,
                        a.id(),
                        a.mediaType(),
                        a.readContentPath()));
            }
        }
        return parts.isEmpty() ? null : parts;
    }

    private String normalizeRole(String role) {
        if ("user".equalsIgnoreCase(role) || "assistant".equalsIgnoreCase(role)
                || "system".equalsIgnoreCase(role) || "tool".equalsIgnoreCase(role)) {
            return role.toLowerCase();
        }
        return "user";
    }
}