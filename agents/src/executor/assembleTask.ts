// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Wire → domain assembly: turn a decoded `task.assign` payload into the
 * idealized {@link Task} the {@link TurnExecutor} consumes.
 *
 * Ported from the Python SDK's `TaskAssignPayload.to_task` plus the message
 * assembly that lived in `LangChainExecutor._build_messages` and
 * `KnowledgeContext.compile_system_prompt_section`. The current engine sends
 * raw prompts + knowledge (not a pre-assembled transcript), so this is where
 * the agent-side assembly happens — kept out of the pure turn loop so the loop
 * stays provider- and engine-shape-agnostic.
 */
import type { Task, TurnMessage } from "../models/index.js";
import type {
  KnowledgeContextWire,
  KnowledgeEntryWire,
  TaskAssignPayload,
} from "../protocol/taskFrames.js";

/** Build the executor-facing {@link Task} from a decoded assign payload. */
export function assembleTask(wire: TaskAssignPayload): Task {
  const systemPrompt = buildSystemPrompt(wire);
  const messages = buildMessages(wire);
  const toolNames = wire.tools.map((t) => t.name);

  return {
    taskId: wire.taskId,
    model: wire.model ? `${wire.model.provider}/${wire.model.modelId}` : "",
    context: {
      systemPrompt,
      messages,
      toolNames,
      // Preserve wire detail the provider/workspace slices will need without
      // widening the frozen Task contract.
      metadata: {
        workflowId: wire.workflowId,
        stepIndex: wire.stepIndex,
        stepName: wire.stepName,
        ...(wire.model ? { model: wire.model } : {}),
        ...(wire.context?.workspace
          ? { workspace: wire.context.workspace }
          : {}),
      },
    },
  };
}

/** Combine the agent-profile system prompt with the compiled knowledge section. */
function buildSystemPrompt(wire: TaskAssignPayload): string {
  const parts: string[] = [];
  if (wire.systemPrompt) {
    parts.push(wire.systemPrompt);
  }
  if (wire.context) {
    const knowledge = compileKnowledgeSection(wire.context);
    if (knowledge) {
      parts.push(
        "\n\n# Project Context\n\n" +
          "The following information provides project standards, " +
          "requirements, and instructions you should follow:\n\n" +
          knowledge,
      );
    }
  }
  return parts.join("\n\n");
}

/**
 * Assemble the non-system turn messages from the step prompt and input,
 * mirroring `_build_messages`. Two shapes are supported:
 * - `input.messages` (a chat array) — step prompt first, then each message;
 * - otherwise — step prompt + `input.prompt` + remaining input as a JSON block.
 */
function buildMessages(wire: TaskAssignPayload): TurnMessage[] {
  const messages: TurnMessage[] = [];
  let userParts: string[] = [];

  if (wire.stepPrompt) {
    userParts.push(wire.stepPrompt);
  }

  const input = wire.input ?? {};
  const chat = input.messages;

  if (Array.isArray(chat)) {
    if (userParts.length > 0) {
      messages.push({ role: "user", content: userParts.join("\n\n") });
      userParts = [];
    }
    for (const raw of chat) {
      const m = (raw ?? {}) as { role?: unknown; content?: unknown };
      const role = typeof m.role === "string" ? m.role : "user";
      const content = typeof m.content === "string" ? m.content : "";
      if (role === "system") {
        messages.push({ role: "system", content });
      } else if (role === "assistant") {
        messages.push({ role: "assistant", content });
      } else {
        messages.push({ role: "user", content });
      }
    }
  } else if (Object.keys(input).length > 0) {
    if (typeof input.prompt === "string") {
      userParts.push(input.prompt);
    }
    const other: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(input)) {
      if (k !== "prompt") {
        other[k] = v;
      }
    }
    if (Object.keys(other).length > 0) {
      userParts.push(
        `\n## Input Data\n\`\`\`json\n${JSON.stringify(other, null, 2)}\n\`\`\``,
      );
    }
  }

  if (userParts.length > 0) {
    messages.push({ role: "user", content: userParts.join("\n\n") });
  }

  return messages;
}

/** Compile knowledge entries into a system-prompt section, grouped by category
 * in a fixed order (port of `compile_system_prompt_section`). */
function compileKnowledgeSection(ctx: KnowledgeContextWire): string {
  if (!ctx.knowledge || ctx.knowledge.length === 0) {
    return "";
  }

  const order: { code: string; title: string }[] = [
    { code: "STANDARD", title: "Standards & Conventions" },
    { code: "REQUIREMENT", title: "Requirements" },
    { code: "ARCHITECTURE", title: "Architecture" },
    { code: "INSTRUCTION", title: "Instructions" },
  ];

  const byCategory = (cat: string): KnowledgeEntryWire[] =>
    ctx.knowledge.filter((e) => e.category === cat);

  const sections: string[] = [];
  for (const { code, title } of order) {
    const entries = byCategory(code);
    if (entries.length > 0) {
      const body = entries.map((e) => `### ${e.name}\n${e.content}`).join("\n\n");
      sections.push(`## ${title}\n\n${body}`);
    }
  }

  return sections.join("\n\n");
}
