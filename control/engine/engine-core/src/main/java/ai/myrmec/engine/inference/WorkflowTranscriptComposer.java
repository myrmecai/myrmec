// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference;

import ai.myrmec.engine.websocket.message.payload.InferenceMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workflow transcript composer (§6.3).
 *
 * <p>Ports {@code buildSystemPrompt()}, {@code compileKnowledgeSection()},
 * and {@code buildMessages()} from
 * {@code agents/src/executor/assembleTask.ts} byte-for-byte:
 * <ul>
 *   <li>system message = profile.systemPrompt + # Project Context block with
 *       knowledge compiled grouped by category in the fixed order
 *       STANDARD → REQUIREMENT → ARCHITECTURE → INSTRUCTION</li>
 *   <li>user message(s) = stepPrompt + input handling:
 *       if input.messages is an array → stepPrompt as leading user message,
 *       then each entry by role; else → stepPrompt + input.prompt + remaining
 *       input rendered as a ## Input Data JSON block</li>
 * </ul></p>
 */
@Slf4j
@Component
public class WorkflowTranscriptComposer implements TranscriptComposer {

    private static final String[] CATEGORY_ORDER = {"STANDARD", "REQUIREMENT", "ARCHITECTURE", "INSTRUCTION"};
    private static final String[] CATEGORY_TITLES = {
            "Standards & Conventions", "Requirements", "Architecture", "Instructions"
    };

    @Override
    public List<InferenceMessage> compose(InferenceRequestSpec spec) {
        List<InferenceMessage> messages = new ArrayList<>();

        // System message
        String systemPrompt = buildSystemPrompt(spec);
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(new InferenceMessage("system", systemPrompt, null, null));
        }

        // User message(s)
        messages.addAll(buildMessages(spec));

        return messages;
    }

    /**
     * Combine the agent-profile system prompt with the compiled knowledge section.
     * Port of {@code buildSystemPrompt} from assembleTask.ts.
     */
    private String buildSystemPrompt(InferenceRequestSpec spec) {
        List<String> parts = new ArrayList<>();
        if (spec.getSystemPrompt() != null && !spec.getSystemPrompt().isBlank()) {
            parts.add(spec.getSystemPrompt());
        }
        String knowledge = compileKnowledgeSection(spec.getKnowledge());
        if (knowledge != null && !knowledge.isEmpty()) {
            parts.add("\n\n# Project Context\n\n"
                    + "The following information provides project standards, "
                    + "requirements, and instructions you should follow:\n\n"
                    + knowledge);
        }
        return String.join("\n\n", parts);
    }

    /**
     * Compile knowledge entries into a system-prompt section, grouped by category
     * in the fixed order STANDARD → REQUIREMENT → ARCHITECTURE → INSTRUCTION.
     * Port of {@code compileKnowledgeSection} from assembleTask.ts.
     */
    private String compileKnowledgeSection(List<InferenceRequestSpec.KnowledgeEntry> knowledge) {
        if (knowledge == null || knowledge.isEmpty()) {
            return "";
        }

        List<String> sections = new ArrayList<>();
        for (int i = 0; i < CATEGORY_ORDER.length; i++) {
            String code = CATEGORY_ORDER[i];
            String title = CATEGORY_TITLES[i];
            final String cat = code;
            List<InferenceRequestSpec.KnowledgeEntry> entries = new ArrayList<>();
            for (InferenceRequestSpec.KnowledgeEntry e : knowledge) {
                if (cat.equals(e.category())) {
                    entries.add(e);
                }
            }
            if (!entries.isEmpty()) {
                List<String> bodies = new ArrayList<>();
                for (InferenceRequestSpec.KnowledgeEntry e : entries) {
                    bodies.add("### " + e.name() + "\n" + e.content());
                }
                sections.add("## " + title + "\n\n" + String.join("\n\n", bodies));
            }
        }
        return String.join("\n\n", sections);
    }

    /**
     * Assemble the non-system turn messages from the step prompt and input.
     * Port of {@code buildMessages} from assembleTask.ts.
     */
    private List<InferenceMessage> buildMessages(InferenceRequestSpec spec) {
        List<InferenceMessage> messages = new ArrayList<>();
        List<String> userParts = new ArrayList<>();

        if (spec.getStepPrompt() != null && !spec.getStepPrompt().isEmpty()) {
            userParts.add(spec.getStepPrompt());
        }

        Map<String, Object> input = spec.getInput() != null ? spec.getInput() : Map.of();
        Object chatObj = input.get("messages");

        if (chatObj instanceof List<?> chat) {
            // input.messages is an array — step prompt first, then each message by role
            if (!userParts.isEmpty()) {
                messages.add(new InferenceMessage("user", String.join("\n\n", userParts), null, null));
                userParts.clear();
            }
            for (Object raw : chat) {
                if (raw instanceof Map<?, ?> m) {
                    String role = m.get("role") instanceof String r ? r : "user";
                    String content = m.get("content") instanceof String c ? c : "";
                    messages.add(new InferenceMessage(role, content, null, null));
                }
            }
        } else if (!input.isEmpty()) {
            // No messages array — stepPrompt + input.prompt + remaining input as JSON block
            Object promptObj = input.get("prompt");
            if (promptObj instanceof String prompt) {
                userParts.add(prompt);
            }
            Map<String, Object> other = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : input.entrySet()) {
                if (!"prompt".equals(e.getKey())) {
                    other.put(e.getKey(), e.getValue());
                }
            }
            if (!other.isEmpty()) {
                userParts.add("\n## Input Data\n```json\n" + toJsonString(other) + "\n```");
            }
        }

        if (!userParts.isEmpty()) {
            messages.add(new InferenceMessage("user", String.join("\n\n", userParts), null, null));
        }

        return messages;
    }

    /**
     * Minimal JSON stringifier for the input data block.
     * Uses Jackson ObjectMapper for proper formatting in production.
     */
    private String toJsonString(Map<String, Object> map) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(map);
        } catch (Exception e) {
            log.warn("Failed to serialize input data to JSON", e);
            return map.toString();
        }
    }
}