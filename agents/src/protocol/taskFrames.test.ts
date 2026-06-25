// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { describe, it, expect } from "vitest";
import {
  taskAssignPayloadSchema,
  taskCancelPayloadSchema,
  taskAccept,
  taskReject,
  taskProgress,
  taskComplete,
  taskFailed,
  toolCall,
  toolResult,
} from "./taskFrames.js";
import { MessageType } from "./messages.js";

describe("taskFrames inbound schemas", () => {
  it("parses a minimal task.assign with defaults", () => {
    const wire = taskAssignPayloadSchema.parse({
      taskId: "t1",
      workflowId: "w1",
      stepIndex: 0,
      stepName: "step",
    });
    expect(wire.input).toEqual({});
    expect(wire.tools).toEqual([]);
    expect(wire.timeoutSeconds).toBe(300);
    expect(wire.model).toBeUndefined();
  });

  it("applies tool and model defaults", () => {
    const wire = taskAssignPayloadSchema.parse({
      taskId: "t1",
      workflowId: "w1",
      stepIndex: 1,
      stepName: "step",
      tools: [{ name: "search" }],
      model: { provider: "openai", modelId: "gpt-4o" },
    });
    expect(wire.tools[0]).toMatchObject({
      name: "search",
      description: "",
      parameters: {},
    });
    expect(wire.model).toMatchObject({ provider: "openai", parameters: {} });
  });

  it("rejects a payload missing taskId", () => {
    const res = taskAssignPayloadSchema.safeParse({
      workflowId: "w1",
      stepIndex: 0,
      stepName: "step",
    });
    expect(res.success).toBe(false);
  });

  it("defaults the cancel reason", () => {
    const wire = taskCancelPayloadSchema.parse({ taskId: "t1" });
    expect(wire.reason).toBe("Cancelled by Engine");
  });
});

describe("taskFrames outbound builders", () => {
  it("builds task.accept", () => {
    const f = taskAccept("t1");
    expect(f.type).toBe(MessageType.TASK_ACCEPT);
    expect(f.payload).toEqual({ taskId: "t1" });
    expect(typeof f.timestamp).toBe("string");
  });

  it("builds task.reject with reason", () => {
    const f = taskReject("t1", "Agent is busy");
    expect(f.type).toBe(MessageType.TASK_REJECT);
    expect(f.payload).toEqual({ taskId: "t1", reason: "Agent is busy" });
  });

  it("clamps and truncates progress", () => {
    expect(taskProgress("t1", 150).payload).toEqual({
      taskId: "t1",
      progress: 100,
    });
    expect(taskProgress("t1", -5).payload).toEqual({
      taskId: "t1",
      progress: 0,
    });
    expect(taskProgress("t1", 33.9, "working").payload).toEqual({
      taskId: "t1",
      progress: 33,
      message: "working",
    });
  });

  it("builds task.complete with an opaque result", () => {
    const f = taskComplete("t1", { response: "done" });
    expect(f.type).toBe(MessageType.TASK_COMPLETE);
    expect(f.payload).toEqual({ taskId: "t1", result: { response: "done" } });
  });

  it("omits optional failure fields when absent", () => {
    expect(taskFailed("t1", { error: "nope" }).payload).toEqual({
      taskId: "t1",
      error: "nope",
    });
    expect(
      taskFailed("t1", {
        error: "nope",
        errorCode: "MAX_ITERATIONS",
        retryAfterSeconds: 5,
      }).payload,
    ).toEqual({
      taskId: "t1",
      error: "nope",
      errorCode: "MAX_ITERATIONS",
      retryAfterSeconds: 5,
    });
  });

  it("builds tool.call", () => {
    const f = toolCall("t1", {
      toolName: "search",
      callId: "c1",
      input: { q: "hi" },
    });
    expect(f.type).toBe(MessageType.TOOL_CALL);
    expect(f.payload).toEqual({
      taskId: "t1",
      toolName: "search",
      callId: "c1",
      input: { q: "hi" },
    });
  });

  it("builds tool.result with null output by default", () => {
    expect(
      toolResult("t1", { callId: "c1", durationMs: 12 }).payload,
    ).toEqual({ taskId: "t1", callId: "c1", durationMs: 12, output: null });
  });

  it("builds tool.result with error", () => {
    expect(
      toolResult("t1", { callId: "c1", durationMs: 3, error: "boom" }).payload,
    ).toEqual({
      taskId: "t1",
      callId: "c1",
      durationMs: 3,
      output: null,
      error: "boom",
    });
  });
});
