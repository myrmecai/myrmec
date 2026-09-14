// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { describe, it, expect, vi, beforeEach } from "vitest";
import { InferenceExecutor } from "./InferenceExecutor.js";
import type { InferenceExecutorOptions } from "./InferenceExecutor.js";
import { SessionRegistry } from "../session/SessionRegistry.js";
import type { Session } from "../session/SessionRegistry.js";
import type {
  ChatModel,
  ConversationMessage,
  ModelResponse,
  ModelStreamChunk,
  ModelToolCall,
  SessionTool,
  ToolSpec,
} from "./types.js";
import type { ExecutionFrameSender } from "./ExecutionFrameSender.js";
import { ApprovalCoordinator } from "./ApprovalCoordinator.js";
import type { ExecutionStartPayload } from "../protocol/unifiedFrames.js";

// ── helpers ───────────────────────────────────────────────────────

/** A scripted ChatModel that returns queued responses in order and records
 * every invoke() call. */
class ScriptedModel implements ChatModel {
  readonly calls: { messages: ConversationMessage[]; tools: ToolSpec[] }[] = [];
  private readonly script: ModelResponse[];

  constructor(script: ModelResponse[]) {
    this.script = [...script];
  }

  async invoke(
    messages: ConversationMessage[],
    tools: ToolSpec[],
  ): Promise<ModelResponse> {
    this.calls.push({ messages: structuredClone(messages), tools });
    const next = this.script.shift();
    if (!next) throw new Error("ScriptedModel ran out of scripted responses");
    return next;
  }
}

/** A streaming model that yields the given chunks one by one. */
class StreamingModel implements ChatModel {
  readonly calls: { messages: ConversationMessage[]; tools: ToolSpec[] }[] = [];
  private readonly chunks: ModelStreamChunk[];

  constructor(chunks: ModelStreamChunk[]) {
    this.chunks = [...chunks];
  }

  async *stream(
    messages: ConversationMessage[],
    tools: ToolSpec[],
  ): AsyncIterable<ModelStreamChunk> {
    this.calls.push({ messages: structuredClone(messages), tools });
    for (const c of this.chunks) {
      yield c;
    }
  }

  async invoke(): Promise<ModelResponse> {
    throw new Error("should not call invoke on a streaming model");
  }
}

/** A model that always throws (provider error). */
class ThrowingModel implements ChatModel {
  constructor(private readonly message = "boom") {}
  async invoke(): Promise<ModelResponse> {
    throw new Error(this.message);
  }
}

/** One captured unified execution frame plus its type string. */
interface CapturedFrame {
  type: string;
  payload: Record<string, unknown>;
}

/** Captures all frames emitted by an {@link ExecutionFrameSender}. */
function makeSenderCapture(): {
  sender: ExecutionFrameSender;
  sent: CapturedFrame[];
} {
  const sent: CapturedFrame[] = [];
  const push = (type: string, payload: Record<string, unknown>) => {
    sent.push({ type, payload: structuredClone(payload) });
    return Promise.resolve();
  };
  const sender: ExecutionFrameSender = {
    sendExecutionAccept: (p) => push("execution.accept", p as Record<string, unknown>),
    sendExecutionReject: (p) => push("execution.reject", p as Record<string, unknown>),
    sendExecutionDelta: (p) => push("execution.delta", p as Record<string, unknown>),
    sendExecutionEvent: (p) => push("execution.event", p as Record<string, unknown>),
    sendExecutionComplete: (p) => push("execution.complete", p as Record<string, unknown>),
    sendExecutionFailed: (p) => push("execution.failed", p as Record<string, unknown>),
    sendExecutionPaused: (p) => push("execution.paused", p as Record<string, unknown>),
    sendExecutionCancelled: (p) => push("execution.cancelled", p as Record<string, unknown>),
    sendExecutionCancel: (p) => push("execution.cancel", p as Record<string, unknown>),
    sendExecutionApprovalRequested: (p) =>
      push("execution.approval.requested", p as Record<string, unknown>),
  };
  return { sender, sent };
}

/** Build a minimal unified ExecutionStartPayload. */
function makeStartPayload(
  overrides: Partial<ExecutionStartPayload> & { stream?: boolean } = {},
): ExecutionStartPayload {
  const { stream, ...rest } = overrides;
  return {
    executionId: "44444444-4444-4444-8444-444444444444",
    sessionId: "33333333-3333-4333-8333-333333333333",
    sequenceNo: 1,
    requestId: "req-1",
    deadline: new Date(Date.now() + 300_000).toISOString(),
    input: {
      messages: [{ role: "user", content: "Hello", parts: null, toolCalls: null }],
      attachments: null,
      conversationContinuation: null,
    },
    toolPolicy: {
      activeToolNames: [],
      approvalMode: null,
    },
    output: {
      stream: stream ?? false,
      responseSequenceNo: 1,
      format: "TEXT",
    },
    ...rest,
  };
}

function fakeTool(
  name: string,
  impl?: (args: Record<string, unknown>) => unknown,
  riskClass: "SAFE" | "DESTRUCTIVE" | "IRREVERSIBLE" = "SAFE",
): SessionTool {
  return {
    name,
    description: `tool ${name}`,
    parameters: { type: "object" },
    invoke: async (args) => impl?.(args) ?? "ok",
    riskClass,
  };
}

const toolCall = (id: string, name: string, args: Record<string, unknown> = {}): ModelToolCall => ({
  id,
  name,
  args,
});

/** A minimal fake SessionRegistry that returns a hand-crafted session for a
 * fixed sessionId. */
class FakeRegistry extends SessionRegistry {
  private readonly sessionMap = new Map<string, Session>();

  constructor() {
    // Pass no-op factories — FakeRegistry overrides open/close/get/has so
    // the factories are never called.
    super({
      chatModelFactory: { resolve: async () => { throw new Error("FakeRegistry does not use chatModelFactory") } },
      sessionToolFactory: { resolve: async () => new Map() },
    });
  }

  set(sessionId: string, session: Session): void {
    this.sessionMap.set(sessionId, session);
  }

  // Stub methods that are not part of the interface but used by InferenceExecutor.
  override async open(): Promise<void> {}
  override close(): void {}
  override get(sessionId: string): Session | undefined {
    return this.sessionMap.get(sessionId);
  }
  override has(sessionId: string): boolean {
    return this.sessionMap.has(sessionId);
  }
}

/** Build a fake session with the given model and tools. */
function makeSession(model: ChatModel, tools: SessionTool[] = [], autoHitl = false): Session {
  const toolMap = new Map<string, SessionTool>();
  for (const t of tools) toolMap.set(t.name, t);
  return {
    sessionId: "33333333-3333-4333-8333-333333333333",
    serviceType: "CONVERSATION",
    projectId: "22222222-2222-2222-8222-222222222222",
    model,
    tools: toolMap,
    knowledgeSourceIds: new Set<string>(),
    autoHitlOnDestructive: autoHitl,
  };
}

// ── tests ──────────────────────────────────────────────────────────

describe("InferenceExecutor unified emissions", () => {
  let registry: FakeRegistry;

  beforeEach(() => {
    registry = new FakeRegistry();
  });

  function makeExecutor(
    opts: Partial<InferenceExecutorOptions> & { wireApprovals?: boolean } = {},
  ): {
    executor: InferenceExecutor;
    sender: ExecutionFrameSender;
    sent: CapturedFrame[];
    approvals?: ApprovalCoordinator;
  } {
    const { sender, sent } = makeSenderCapture();
    let approvals: ApprovalCoordinator | undefined;
    if (opts.wireApprovals) {
      approvals = new ApprovalCoordinator({ sender });
    }
    const { wireApprovals: _, ...executorOpts } = opts;
    const executor = new InferenceExecutor({
      registry: registry as SessionRegistry,
      sender,
      logger: { debug: () => {}, info: () => {}, warn: () => {}, error: () => {} },
      ...(approvals ? { approvals } : {}),
      ...executorOpts,
    });
    return { executor, sender, sent, ...(approvals ? { approvals } : {}) };
  }

  // ── unknown session → execution.failed ─────────────────────────

  it("emits execution.failed with SESSION_NOT_OPEN when no session is registered", async () => {
    const { executor, sent } = makeExecutor();

    await executor.handleStart(makeStartPayload({ sessionId: "99999999-9999-4999-8999-999999999999" }));

    const failed = sent.find((f) => f.type === "execution.failed");
    expect(failed).toBeDefined();
    expect(failed!.payload.error).toMatchObject({
      code: "SESSION_NOT_OPEN",
    });
  }, 15000);

  // ── single-shot (stream=false) → execution.complete ─────────────

  it("emits execution.accept then execution.complete when stream=false", async () => {
    const model = new ScriptedModel([{ content: "the answer is 42" }]);
    registry.set("33333333-3333-4333-8333-333333333333", makeSession(model));

    const { executor, sent } = makeExecutor();

    await executor.handleStart(makeStartPayload({ stream: false }));

    const accept = sent.find((f) => f.type === "execution.accept");
    expect(accept).toBeDefined();
    expect(accept!.payload).toMatchObject({ executionId: "44444444-4444-4444-8444-444444444444" });

    const complete = sent.find((f) => f.type === "execution.complete");
    expect(complete).toBeDefined();
    expect(complete!.payload.result).toMatchObject({
      content: "the answer is 42",
    });
  });

  // ── streaming (stream=true) → execution.delta + execution.complete

  it("emits execution.delta per chunk then execution.complete when stream=true", async () => {
    const model = new StreamingModel([
      { content: "Hello" },
      { content: " world" },
      { content: "!", usage: { completionTokens: 3 } },
    ]);
    registry.set("33333333-3333-4333-8333-333333333333", makeSession(model));

    const { executor, sent } = makeExecutor();

    await executor.handleStart(makeStartPayload({ stream: true }));

    const deltas = sent.filter((f) => f.type === "execution.delta");
    expect(deltas).toHaveLength(3);
    expect(deltas[0].payload).toMatchObject({ index: 0, content: "Hello" });
    expect(deltas[1].payload).toMatchObject({ index: 1, content: " world" });
    expect(deltas[2].payload).toMatchObject({ index: 2, content: "!" });

    // Delta indices are strictly monotonic.
    expect(deltas.map((d) => d.payload.index)).toEqual([0, 1, 2]);

    const complete = sent.find((f) => f.type === "execution.complete");
    expect(complete).toBeDefined();
    expect(complete!.payload.result).toMatchObject({ content: "Hello world!" });
    expect(complete!.payload.usage).toMatchObject({ outputTokens: 3 });
  });

  // ── tool loop is internal: no tool_call/tool_result emissions ──

  it("runs tools internally and emits only execution.complete", async () => {
    const model = new ScriptedModel([
      { content: "let me check", toolCalls: [toolCall("c1", "add", { a: 2, b: 3 })] },
      { content: "the sum is 5" },
    ]);
    const add = fakeTool("add", (args) => (args.a as number) + (args.b as number));
    registry.set("33333333-3333-4333-8333-333333333333", makeSession(model, [add]));

    const { executor, sent } = makeExecutor();

    await executor.handleStart(
      makeStartPayload({
        input: {
          messages: [{ role: "user", content: "add", parts: null, toolCalls: null }],
          attachments: null,
          conversationContinuation: null,
        },
        toolPolicy: { activeToolNames: ["add"], approvalMode: null },
      }),
    );

    const legacyToolFrames = sent.filter(
      (f) => f.type.includes("tool_call") || f.type.includes("tool_result"),
    );
    expect(legacyToolFrames).toHaveLength(0);

    const complete = sent.find((f) => f.type === "execution.complete");
    expect(complete).toBeDefined();
    expect(complete!.payload.result).toMatchObject({ content: "the sum is 5" });

    // Second invoke should include the tool result message.
    const secondMessages = model.calls[1].messages;
    expect(secondMessages.some((m) => m.role === "tool" && m.toolCallId === "c1")).toBe(true);
  });

  // ── cancel → execution.cancelled ────────────────────────────────

  it("emits execution.cancelled when cancelled mid-stream", async () => {
    const chunks: ModelStreamChunk[] = [{ content: "partial" }, { content: "..." }];
    class SlowStreamModel implements ChatModel {
      async *stream(): AsyncIterable<ModelStreamChunk> {
        for (const c of chunks) {
          await new Promise((r) => setTimeout(r, 10));
          yield c;
        }
      }
      async invoke(): Promise<ModelResponse> {
        return { content: "fallback" };
      }
    }
    const model = new SlowStreamModel();
    registry.set("33333333-3333-4333-8333-333333333333", makeSession(model));

    const { executor, sent } = makeExecutor();

    const promise = executor.handleStart(makeStartPayload({ stream: true }));
    await new Promise((r) => setTimeout(r, 15));
    executor.handleCancel({
      executionId: "44444444-4444-4444-8444-444444444444",
      dispatchId: "44444444-4444-4444-8444-444444444444",
      reasonCode: "USER_REQUEST",
      requestedAt: new Date().toISOString(),
      gracePeriodSeconds: 5,
    });

    await promise;

    const cancelled = sent.find((f) => f.type === "execution.cancelled");
    expect(cancelled).toBeDefined();
    expect(cancelled!.payload).toMatchObject({
      executionId: "44444444-4444-4444-8444-444444444444",
      reasonCode: "USER_REQUEST",
    });
  });

  // ── provider error → execution.failed ──────────────────────────

  it("emits execution.failed with PROVIDER_ERROR when the model throws", async () => {
    const model = new ThrowingModel("connection refused");
    registry.set("33333333-3333-4333-8333-333333333333", makeSession(model));

    const { executor, sent } = makeExecutor();

    await executor.handleStart(makeStartPayload({ stream: false }));

    const failed = sent.find((f) => f.type === "execution.failed");
    expect(failed).toBeDefined();
    expect(failed!.payload.error).toMatchObject({
      code: "PROVIDER_ERROR",
      message: "connection refused",
      retryable: true,
    });
  });

  // ── accept emitted before complete ─────────────────────────────

  it("emits execution.accept before execution.complete", async () => {
    const model = new ScriptedModel([{ content: "done" }]);
    registry.set("33333333-3333-4333-8333-333333333333", makeSession(model));

    const { executor, sent } = makeExecutor();

    await executor.handleStart(makeStartPayload({ stream: false }));

    const acceptIdx = sent.findIndex((f) => f.type === "execution.accept");
    const completeIdx = sent.findIndex((f) => f.type === "execution.complete");
    expect(acceptIdx).toBeGreaterThanOrEqual(0);
    expect(completeIdx).toBeGreaterThan(acceptIdx);
  });

  // ── handleCancel no-op for unknown executionId ────────────────────

  it("handleCancel is a no-op when no in-flight execution matches", () => {
    const { executor } = makeExecutor();
    executor.handleCancel({
      executionId: "00000000-0000-4000-8000-000000000000",
      dispatchId: "00000000-0000-4000-8000-000000000000",
      reasonCode: "USER_REQUEST",
      requestedAt: new Date().toISOString(),
      gracePeriodSeconds: 5,
    });
    expect(executor.inFlightCount).toBe(0);
  });

  // ── isBusy / inFlightCount ──────────────────────────────────────

  it("tracks in-flight count via isBusy", async () => {
    class SlowModel implements ChatModel {
      async invoke(): Promise<ModelResponse> {
        await new Promise((r) => setTimeout(r, 50));
        return { content: "done" };
      }
    }
    const model = new SlowModel();
    registry.set("33333333-3333-4333-8333-333333333333", makeSession(model));

    const { executor } = makeExecutor();

    const promise = executor.handleStart(makeStartPayload({ stream: false }));
    await new Promise((r) => setTimeout(r, 5));
    expect(executor.isBusy).toBe(true);

    await promise;
    expect(executor.isBusy).toBe(false);
    expect(executor.inFlightCount).toBe(0);
  });

  // ── HITL: DESTRUCTIVE tool gated by approval ───────────────────

  it("emits execution.approval.requested before execution.paused for a DESTRUCTIVE tool", async () => {
    const model = new ScriptedModel([
      { content: "deleting", toolCalls: [toolCall("c1", "rm", { path: "/tmp/x" })] },
      { content: "done" },
    ]);
    const rm = fakeTool("rm", () => "deleted", "DESTRUCTIVE");
    registry.set("33333333-3333-4333-8333-333333333333", makeSession(model, [rm], true));

    const { executor, sent, approvals } = makeExecutor({ wireApprovals: true });

    const approvalPromise = vi.waitFor(() => {
      const frame = sent.find((f) => f.type === "execution.approval.requested");
      if (!frame) throw new Error("execution.approval.requested not sent yet");
      return frame;
    }, { timeout: 5000 });
    const handlePromise = executor.handleStart(
      makeStartPayload({
        toolPolicy: { activeToolNames: ["rm"], approvalMode: null },
        stream: false,
      }),
    );

    const approvalFrame = await approvalPromise;
    expect(approvalFrame).toBeDefined();
    const approvalRequestId = (approvalFrame!.payload as { approvalRequestId: string }).approvalRequestId;
    approvals!.resolve({
      conversationId: "req-1",
      clientRequestId: approvalRequestId,
      decision: "APPROVED",
    });

    await handlePromise;

    const complete = sent.find((f) => f.type === "execution.complete");
    expect(complete).toBeDefined();
  });

  it("skips DESTRUCTIVE tool execution when approval is rejected", async () => {
    const model = new ScriptedModel([
      { content: "deleting", toolCalls: [toolCall("c1", "rm", { path: "/tmp/x" })] },
      { content: "I will not delete the file." },
    ]);
    const rm = fakeTool("rm", () => "deleted", "DESTRUCTIVE");
    let invokeCalled = false;
    const rmTracked: SessionTool = {
      ...rm,
      invoke: async () => { invokeCalled = true; return "deleted"; },
    };
    registry.set("33333333-3333-4333-8333-333333333333", makeSession(model, [rmTracked], true));

    const { executor, sent, approvals } = makeExecutor({ wireApprovals: true });

    const approvalPromise = vi.waitFor(() => {
      const frame = sent.find((f) => f.type === "execution.approval.requested");
      if (!frame) throw new Error("execution.approval.requested not sent yet");
      return frame;
    }, { timeout: 5000 });
    const handlePromise = executor.handleStart(
      makeStartPayload({
        toolPolicy: { activeToolNames: ["rm"], approvalMode: null },
        stream: false,
      }),
    );

    const approvalFrame = await approvalPromise;
    const approvalRequestId = (approvalFrame!.payload as { approvalRequestId: string }).approvalRequestId;
    approvals!.resolve({
      conversationId: "req-1",
      clientRequestId: approvalRequestId,
      decision: "REJECTED",
      comment: "no way",
    });

    await handlePromise;

    expect(invokeCalled).toBe(false);

    const complete = sent.find((f) => f.type === "execution.complete");
    expect(complete).toBeDefined();
    expect(complete!.payload.result).toMatchObject({ content: "I will not delete the file." });
  });

  it("executes SAFE tools immediately without approval even when autoHitlOnDestructive=true", async () => {
    const model = new ScriptedModel([
      { content: "checking", toolCalls: [toolCall("c1", "search", { q: "test" })] },
      { content: "found it" },
    ]);
    const search = fakeTool("search", () => "result", "SAFE");
    registry.set("33333333-3333-4333-8333-333333333333", makeSession(model, [search], true));

    const { executor, sent } = makeExecutor({ wireApprovals: true });

    await executor.handleStart(
      makeStartPayload({
        toolPolicy: { activeToolNames: ["search"], approvalMode: null },
        stream: false,
      }),
    );

    const approvalFrame = sent.find((f) => f.type === "execution.approval.requested");
    expect(approvalFrame).toBeUndefined();

    const complete = sent.find((f) => f.type === "execution.complete");
    expect(complete).toBeDefined();
  });

  it("executes DESTRUCTIVE tools without approval when autoHitlOnDestructive=false", async () => {
    const model = new ScriptedModel([
      { content: "deleting", toolCalls: [toolCall("c1", "rm", { path: "/tmp/x" })] },
      { content: "done" },
    ]);
    const rm = fakeTool("rm", () => "deleted", "DESTRUCTIVE");
    registry.set("33333333-3333-4333-8333-333333333333", makeSession(model, [rm], false));

    const { executor, sent } = makeExecutor({ wireApprovals: true });

    await executor.handleStart(
      makeStartPayload({
        toolPolicy: { activeToolNames: ["rm"], approvalMode: null },
        stream: false,
      }),
    );

    const approvalFrame = sent.find((f) => f.type === "execution.approval.requested");
    expect(approvalFrame).toBeUndefined();

    const complete = sent.find((f) => f.type === "execution.complete");
    expect(complete).toBeDefined();
  });

  it("executes DESTRUCTIVE tools without approval when no ApprovalCoordinator is wired", async () => {
    const model = new ScriptedModel([
      { content: "deleting", toolCalls: [toolCall("c1", "rm", { path: "/tmp/x" })] },
      { content: "done" },
    ]);
    const rm = fakeTool("rm", () => "deleted", "DESTRUCTIVE");
    registry.set("33333333-3333-4333-8333-333333333333", makeSession(model, [rm], true));

    const { executor, sent } = makeExecutor();

    await executor.handleStart(
      makeStartPayload({
        toolPolicy: { activeToolNames: ["rm"], approvalMode: null },
        stream: false,
      }),
    );

    const approvalFrame = sent.find((f) => f.type === "execution.approval.requested");
    expect(approvalFrame).toBeUndefined();

    const complete = sent.find((f) => f.type === "execution.complete");
    expect(complete).toBeDefined();
  });
});