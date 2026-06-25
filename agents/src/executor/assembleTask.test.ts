// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { describe, it, expect } from "vitest";
import { assembleTask } from "./assembleTask.js";
import { taskAssignPayloadSchema } from "../protocol/taskFrames.js";

/** Parse a partial wire object through the schema so defaults are applied. */
function wire(overrides: Record<string, unknown>) {
  return taskAssignPayloadSchema.parse({
    taskId: "t1",
    workflowId: "w1",
    stepIndex: 0,
    stepName: "step",
    ...overrides,
  });
}

describe("assembleTask", () => {
  it("maps identity, model, and tool names", () => {
    const task = assembleTask(
      wire({
        model: { provider: "openai", modelId: "gpt-4o" },
        tools: [{ name: "search" }, { name: "calc" }],
      }),
    );
    expect(task.taskId).toBe("t1");
    expect(task.model).toBe("openai/gpt-4o");
    expect(task.context.toolNames).toEqual(["search", "calc"]);
    expect(task.context.metadata).toMatchObject({
      workflowId: "w1",
      stepIndex: 0,
      stepName: "step",
    });
  });

  it("uses an empty model string when no model is present", () => {
    expect(assembleTask(wire({})).model).toBe("");
  });

  it("combines system prompt with a compiled knowledge section", () => {
    const task = assembleTask(
      wire({
        systemPrompt: "You are an agent.",
        context: {
          knowledge: [
            { category: "STANDARD", name: "Style", content: "Use tabs." },
            { category: "REQUIREMENT", name: "R1", content: "Must be fast." },
          ],
        },
      }),
    );
    expect(task.context.systemPrompt).toContain("You are an agent.");
    expect(task.context.systemPrompt).toContain("# Project Context");
    expect(task.context.systemPrompt).toContain("## Standards & Conventions");
    expect(task.context.systemPrompt).toContain("### Style");
    expect(task.context.systemPrompt).toContain("## Requirements");
    // STANDARD must precede REQUIREMENT (fixed category order).
    expect(task.context.systemPrompt.indexOf("Standards")).toBeLessThan(
      task.context.systemPrompt.indexOf("Requirements"),
    );
  });

  it("emits only the step prompt as a user message when input is empty", () => {
    const task = assembleTask(wire({ stepPrompt: "Do the thing." }));
    expect(task.context.messages).toEqual([
      { role: "user", content: "Do the thing." },
    ]);
  });

  it("maps an input.messages chat array, keeping the step prompt first", () => {
    const task = assembleTask(
      wire({
        stepPrompt: "Context.",
        input: {
          messages: [
            { role: "user", content: "Hello" },
            { role: "assistant", content: "Hi there" },
            { role: "system", content: "stay terse" },
          ],
        },
      }),
    );
    expect(task.context.messages).toEqual([
      { role: "user", content: "Context." },
      { role: "user", content: "Hello" },
      { role: "assistant", content: "Hi there" },
      { role: "system", content: "stay terse" },
    ]);
  });

  it("renders prompt + remaining input as a JSON data block", () => {
    const task = assembleTask(
      wire({
        input: { prompt: "Summarize", document: "abc", topK: 3 },
      }),
    );
    expect(task.context.messages).toHaveLength(1);
    const content = task.context.messages[0].content;
    expect(content).toContain("Summarize");
    expect(content).toContain("## Input Data");
    expect(content).toContain('"document": "abc"');
    expect(content).toContain('"topK": 3');
    // The prompt key is not duplicated into the data block.
    expect(content).not.toContain('"prompt"');
  });
});
