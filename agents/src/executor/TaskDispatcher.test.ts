// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { describe, it, expect, vi } from "vitest";
import { TaskDispatcher } from "./TaskDispatcher.js";
import type {
  ChatModel,
  ConversationMessage,
  ModelResponse,
  Tool,
} from "./types.js";
import type { Envelope } from "../protocol/envelope.js";
import { MessageType } from "../protocol/messages.js";

/** Records every frame the dispatcher sends. */
function recorder() {
  const frames: Envelope[] = [];
  const send = (frame: Envelope) => {
    frames.push(frame);
    return Promise.resolve();
  };
  const typesSent = () => frames.map((f) => f.type);
  const firstOf = (type: string) => frames.find((f) => f.type === type);
  return { frames, send, typesSent, firstOf };
}

/** A minimal valid task.assign payload. */
function assignPayload(overrides: Record<string, unknown> = {}) {
  return {
    taskId: "t1",
    workflowId: "w1",
    stepIndex: 0,
    stepName: "step",
    model: { provider: "openai", modelId: "gpt-4o" },
    ...overrides,
  };
}

/** A model that returns a final answer immediately. */
class FinalModel implements ChatModel {
  constructor(private readonly answer = "done") {}
  async invoke(): Promise<ModelResponse> {
    return { content: this.answer };
  }
}

/** A model that always throws (provider error). */
class ThrowingModel implements ChatModel {
  async invoke(): Promise<ModelResponse> {
    throw new Error("provider down");
  }
}

/** A model whose invoke never resolves until released — keeps the agent busy. */
class HangingModel implements ChatModel {
  released = false;
  private release!: () => void;
  readonly gate = new Promise<void>((r) => (this.release = r));
  async invoke(): Promise<ModelResponse> {
    await this.gate;
    return { content: "done" };
  }
  finish() {
    this.released = true;
    this.release();
  }
}

/** Requests one tool on the first call, then finishes on the second. */
class ToolThenDoneModel implements ChatModel {
  private calls = 0;
  async invoke(_m: ConversationMessage[]): Promise<ModelResponse> {
    this.calls += 1;
    if (this.calls === 1) {
      return { toolCalls: [{ id: "c1", name: "search", args: { q: "hi" } }] };
    }
    return { content: "final" };
  }
}

/** Keeps requesting a tool so the loop iterates (used for cancellation). */
class LoopingModel implements ChatModel {
  async invoke(): Promise<ModelResponse> {
    return { toolCalls: [{ id: "c", name: "search", args: {} }] };
  }
}

function fakeTool(name: string, result: unknown = "ok"): Tool {
  return { name, invoke: async () => result };
}

describe("TaskDispatcher", () => {
  it("accepts and completes a task", async () => {
    const { send, typesSent, firstOf } = recorder();
    const d = new TaskDispatcher({ send, model: new FinalModel("hello") });

    d.handleAssign(assignPayload());

    await vi.waitFor(() =>
      expect(typesSent()).toContain(MessageType.TASK_COMPLETE),
    );
    expect(typesSent()[0]).toBe(MessageType.TASK_ACCEPT);
    expect(firstOf(MessageType.TASK_COMPLETE)?.payload).toEqual({
      taskId: "t1",
      result: { response: "hello" },
    });
    expect(d.isBusy).toBe(false);
  });

  it("rejects a second task while busy", async () => {
    const { send, frames, typesSent } = recorder();
    const model = new HangingModel();
    const d = new TaskDispatcher({ send, model });

    d.handleAssign(assignPayload({ taskId: "t1" }));
    // The first task accepts and then hangs in the model invoke.
    await vi.waitFor(() => expect(d.isBusy).toBe(true));

    d.handleAssign(assignPayload({ taskId: "t2" }));
    await vi.waitFor(() =>
      expect(typesSent()).toContain(MessageType.TASK_REJECT),
    );
    const reject = frames.find((f) => f.type === MessageType.TASK_REJECT);
    expect(reject?.payload).toEqual({ taskId: "t2", reason: "Agent is busy" });

    // Let the first task finish so it doesn't leak.
    model.finish();
    await vi.waitFor(() => expect(d.isBusy).toBe(false));
  });

  it("maps an executor failure to task.failed", async () => {
    const { send, firstOf, typesSent } = recorder();
    const d = new TaskDispatcher({ send, model: new ThrowingModel() });

    d.handleAssign(assignPayload());

    await vi.waitFor(() =>
      expect(typesSent()).toContain(MessageType.TASK_FAILED),
    );
    const failed = firstOf(MessageType.TASK_FAILED);
    expect(failed?.payload).toMatchObject({
      taskId: "t1",
      errorCode: "PROVIDER_ERROR",
    });
  });

  it("emits tool.call and tool.result frames during execution", async () => {
    const { send, firstOf, typesSent } = recorder();
    const d = new TaskDispatcher({
      send,
      model: new ToolThenDoneModel(),
      tools: [fakeTool("search", "result-text")],
    });

    d.handleAssign(assignPayload({ tools: [{ name: "search" }] }));

    await vi.waitFor(() =>
      expect(typesSent()).toContain(MessageType.TASK_COMPLETE),
    );
    expect(typesSent()).toContain(MessageType.TOOL_CALL);
    expect(typesSent()).toContain(MessageType.TOOL_RESULT);
    expect(firstOf(MessageType.TOOL_CALL)?.payload).toMatchObject({
      taskId: "t1",
      toolName: "search",
      callId: "c1",
      input: { q: "hi" },
    });
    expect(firstOf(MessageType.TOOL_RESULT)?.payload).toMatchObject({
      taskId: "t1",
      callId: "c1",
      output: { result: "result-text" },
    });
  });

  it("filters tools to the engine-authorized set", async () => {
    const { send, typesSent } = recorder();
    // Model requests `search`, but the task does not authorize it → recorded
    // as an unknown tool, still surfaced as a tool frame.
    const d = new TaskDispatcher({
      send,
      model: new ToolThenDoneModel(),
      tools: [fakeTool("search")],
    });

    // No tools authorized for this task.
    d.handleAssign(assignPayload({ tools: [] }));

    await vi.waitFor(() =>
      expect(typesSent()).toContain(MessageType.TASK_COMPLETE),
    );
    // The tool was still requested by the model, so a tool.result with an
    // error (unknown tool) is emitted.
    const toolResult = typesSent().filter(
      (t) => t === MessageType.TOOL_RESULT,
    );
    expect(toolResult.length).toBe(1);
  });

  it("cancels the current task without sending a terminal frame", async () => {
    const { send, typesSent } = recorder();
    const d = new TaskDispatcher({
      send,
      model: new LoopingModel(),
      tools: [fakeTool("search")],
      maxIterations: 50,
    });

    d.handleAssign(assignPayload({ tools: [{ name: "search" }] }));
    await vi.waitFor(() => expect(d.isBusy).toBe(true));

    d.handleCancel({ taskId: "t1", reason: "stop" });

    await vi.waitFor(() => expect(d.isBusy).toBe(false));
    expect(typesSent()).not.toContain(MessageType.TASK_COMPLETE);
    expect(typesSent()).not.toContain(MessageType.TASK_FAILED);
    expect(typesSent()[0]).toBe(MessageType.TASK_ACCEPT);
  });

  it("drops a malformed assign payload without sending or going busy", async () => {
    const { send, frames } = recorder();
    const d = new TaskDispatcher({ send, model: new FinalModel() });

    d.handleAssign({ not: "a task" });

    await Promise.resolve();
    expect(frames).toHaveLength(0);
    expect(d.isBusy).toBe(false);
  });

  it("ignores a cancel for a non-current task", () => {
    const { send } = recorder();
    const d = new TaskDispatcher({ send, model: new FinalModel() });
    // Should not throw.
    expect(() => d.handleCancel({ taskId: "other" })).not.toThrow();
  });
});
