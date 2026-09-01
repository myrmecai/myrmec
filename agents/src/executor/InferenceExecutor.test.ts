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
import type { Envelope } from "../protocol/envelope.js";
import { MessageType } from "../protocol/messages.js";
import { ApprovalCoordinator } from "./ApprovalCoordinator.js";
import type {
  InferenceAssignPayload,
  InferenceMessage,
} from "../protocol/inferenceFrames.js";

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

/** Captures all sent frames for assertion. */
function makeSendCapture() {
  const sent: Envelope[] = [];
  const send = vi.fn(async (frame: Envelope) => {
    sent.push(frame);
  });
  return { send, sent };
}

/** Build a minimal InferenceAssignPayload. */
function makeAssignPayload(overrides: Partial<InferenceAssignPayload> = {}): InferenceAssignPayload {
  return {
    requestId: "req-1",
    sessionId: "sess-1",
    serviceType: "CONVERSATION",
    messages: [{ role: "user" as const, content: "Hello" }],
    activeToolNames: [],
    generation: null,
    timeoutSeconds: null,
    stream: false,
    response: { sequenceNo: 1 },
    ...overrides,
  };
}

function makeMessages(role: string, content: string): InferenceMessage { return { role: role as InferenceMessage["role"], content }; } void makeMessages;

function fakeTool(name: string, impl?: (args: Record<string, unknown>) => unknown, riskClass: "SAFE" | "DESTRUCTIVE" | "IRREVERSIBLE" = "SAFE"): SessionTool {
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
    sessionId: "sess-1",
    serviceType: "CONVERSATION",
    projectId: "proj-1",
    model,
    tools: toolMap,
    knowledgeSourceIds: new Set<string>(),
    autoHitlOnDestructive: autoHitl,
  };
}

// ── tests ──────────────────────────────────────────────────────────

describe("InferenceExecutor", () => {
  let registry: FakeRegistry;

  beforeEach(() => {
    registry = new FakeRegistry();
  });

  function makeExecutor(opts: Partial<InferenceExecutorOptions> & { wireApprovals?: boolean } = {}): {
    executor: InferenceExecutor;
    send: ReturnType<typeof makeSendCapture>["send"];
    sent: Envelope[];
    approvals?: ApprovalCoordinator;
  } {
    const { send, sent } = makeSendCapture();
    // Create an ApprovalCoordinator that shares the executor's send sink,
    // so approval.request frames are captured in `sent`.
    let approvals: ApprovalCoordinator | undefined;
    if (opts.wireApprovals) {
      approvals = new ApprovalCoordinator({ send });
    }
    const { wireApprovals: _, ...executorOpts } = opts;
    const executor = new InferenceExecutor({
      registry: registry as SessionRegistry,
      send,
      logger: { debug: () => {}, info: () => {}, warn: () => {}, error: () => {} },
      ...(approvals ? { approvals } : {}),
      ...executorOpts,
    });
    return { executor, send, sent, ...(approvals ? { approvals } : {}) };
  }

  // ── unknown session → inference.failed ─────────────────────────

  it("emits inference.failed with SESSION_NOT_OPEN when no session is registered", async () => {
    // Don't set any session in the registry.
    const { executor, sent } = makeExecutor();

    // The executor retries for ~10s (20 × 500ms) before giving up — the
    // vitest default timeout is 5s so we override it to 15s.
    await executor.handleAssign(makeAssignPayload({ sessionId: "unknown" }));

    const failed = sent.find((f) => f.type === MessageType.INFERENCE_FAILED);
    expect(failed).toBeDefined();
    expect(failed!.payload).toMatchObject({
      requestId: "req-1",
      sessionId: "unknown",
      errorCode: "SESSION_NOT_OPEN",
    });
  }, 15000);

  // ── single-shot (stream=false) → inference.complete ─────────────

  it("emits inference.accept then inference.complete when stream=false and model returns a final answer", async () => {
    const model = new ScriptedModel([{ content: "the answer is 42" }]);
    registry.set("sess-1", makeSession(model));

    const { executor, sent } = makeExecutor();

    await executor.handleAssign(makeAssignPayload({ stream: false }));

    const accept = sent.find((f) => f.type === MessageType.INFERENCE_ACCEPT);
    expect(accept).toBeDefined();
    expect(accept!.payload).toMatchObject({ requestId: "req-1", sessionId: "sess-1" });

    const complete = sent.find((f) => f.type === MessageType.INFERENCE_COMPLETE);
    expect(complete).toBeDefined();
    expect(complete!.payload).toMatchObject({
      requestId: "req-1",
      sessionId: "sess-1",
      sequenceNo: 1,
      content: "the answer is 42",
    });
  });

  // ── streaming (stream=true) → inference.delta + inference.complete

  it("emits inference.delta per chunk then inference.complete when stream=true", async () => {
    const model = new StreamingModel([
      { content: "Hello" },
      { content: " world" },
      { content: "!", usage: { completionTokens: 3 } },
    ]);
    registry.set("sess-1", makeSession(model));

    const { executor, sent } = makeExecutor();

    await executor.handleAssign(makeAssignPayload({ stream: true }));

    const deltas = sent.filter((f) => f.type === MessageType.INFERENCE_DELTA);
    expect(deltas).toHaveLength(3);
    expect(deltas[0].payload).toMatchObject({ deltaIndex: 0, content: "Hello" });
    expect(deltas[1].payload).toMatchObject({ deltaIndex: 1, content: " world" });
    expect(deltas[2].payload).toMatchObject({ deltaIndex: 2, content: "!" });

    const complete = sent.find((f) => f.type === MessageType.INFERENCE_COMPLETE);
    expect(complete).toBeDefined();
    expect(complete!.payload).toMatchObject({
      content: "Hello world!",
      tokenCount: 3,
    });
  });

  // ── tool_call + tool_result ─────────────────────────────────────

  it("emits inference.tool_call and inference.tool_result, then feeds result back and completes", async () => {
    const model = new ScriptedModel([
      { content: "let me check", toolCalls: [toolCall("c1", "add", { a: 2, b: 3 })] },
      { content: "the sum is 5" },
    ]);
    const add = fakeTool("add", (args) => (args.a as number) + (args.b as number));
    registry.set("sess-1", makeSession(model, [add]));

    const { executor, sent } = makeExecutor();

    await executor.handleAssign(
      makeAssignPayload({ activeToolNames: ["add"], stream: false }),
    );

    const toolCallFrame = sent.find((f) => f.type === MessageType.INFERENCE_TOOL_CALL);
    expect(toolCallFrame).toBeDefined();
    expect(toolCallFrame!.payload).toMatchObject({
      toolCallId: "c1",
      name: "add",
      args: { a: 2, b: 3 },
    });

    const toolResultFrame = sent.find((f) => f.type === MessageType.INFERENCE_TOOL_RESULT);
    expect(toolResultFrame).toBeDefined();
    expect(toolResultFrame!.payload).toMatchObject({
      toolCallId: "c1",
      result: "5",
      isError: false,
    });

    const complete = sent.find((f) => f.type === MessageType.INFERENCE_COMPLETE);
    expect(complete).toBeDefined();
    expect(complete!.payload).toMatchObject({ content: "the sum is 5" });

    // Second invoke should include the tool result message.
    const secondMessages = model.calls[1].messages;
    expect(secondMessages.some((m) => m.role === "tool" && m.toolCallId === "c1")).toBe(true);
  });

  // ── cancel → inference.cancelled ────────────────────────────────

  it("emits inference.cancelled with partial content when cancelled mid-stream", async () => {
    const chunks: ModelStreamChunk[] = [{ content: "partial" }, { content: "..." }];
    // Use a streaming model with a delay between chunks so we can cancel.
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
    registry.set("sess-1", makeSession(model));

    const { executor, sent } = makeExecutor();

    // Start the streaming assign (don't await yet).
    const promise = executor.handleAssign(makeAssignPayload({ stream: true }));

    // Give it time to process the first chunk.
    await new Promise((r) => setTimeout(r, 15));

    // Cancel it.
    executor.handleCancel({ requestId: "req-1", sessionId: "sess-1" });

    await promise;

    const cancelled = sent.find((f) => f.type === MessageType.INFERENCE_CANCELLED);
    expect(cancelled).toBeDefined();
    expect(cancelled!.payload).toMatchObject({
      requestId: "req-1",
      sessionId: "sess-1",
      sequenceNo: 1,
    });
    // partialContent should contain at least "partial" from the first chunk.
    expect((cancelled!.payload as { partialContent?: string }).partialContent).toContain("partial");
  });

  // ── provider error → inference.failed ──────────────────────────

  it("emits inference.failed with PROVIDER_ERROR when the model throws", async () => {
    const model = new ThrowingModel("connection refused");
    registry.set("sess-1", makeSession(model));

    const { executor, sent } = makeExecutor();

    await executor.handleAssign(makeAssignPayload({ stream: false }));

    const failed = sent.find((f) => f.type === MessageType.INFERENCE_FAILED);
    expect(failed).toBeDefined();
    expect(failed!.payload).toMatchObject({
      requestId: "req-1",
      errorCode: "PROVIDER_ERROR",
      message: "connection refused",
    });
  });

  // ── accept emitted before complete ─────────────────────────────

  it("emits inference.accept before inference.complete", async () => {
    const model = new ScriptedModel([{ content: "done" }]);
    registry.set("sess-1", makeSession(model));

    const { executor, sent } = makeExecutor();

    await executor.handleAssign(makeAssignPayload({ stream: false }));

    const acceptIdx = sent.findIndex((f) => f.type === MessageType.INFERENCE_ACCEPT);
    const completeIdx = sent.findIndex((f) => f.type === MessageType.INFERENCE_COMPLETE);
    expect(acceptIdx).toBeGreaterThanOrEqual(0);
    expect(completeIdx).toBeGreaterThan(acceptIdx);
  });

  // ── handleCancel no-op for unknown requestId ────────────────────

  it("handleCancel is a no-op when no in-flight request matches", () => {
    const { executor } = makeExecutor();
    // Should not throw.
    executor.handleCancel({ requestId: "nonexistent", sessionId: "sess-1" });
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
    registry.set("sess-1", makeSession(model));

    const { executor } = makeExecutor();

    const promise = executor.handleAssign(makeAssignPayload({ stream: false }));
    // While in-flight, isBusy should be true.
    // (There's a brief window after accept but before the model resolves.)
    await new Promise((r) => setTimeout(r, 5));
    expect(executor.isBusy).toBe(true);

    await promise;
    expect(executor.isBusy).toBe(false);
    expect(executor.inFlightCount).toBe(0);
  });

  // ── HITL: DESTRUCTIVE tool gated by approval ───────────────────

  it("requests approval before executing a DESTRUCTIVE tool when autoHitlOnDestructive=true", async () => {
    const model = new ScriptedModel([
      { content: "deleting", toolCalls: [toolCall("c1", "rm", { path: "/tmp/x" })] },
      { content: "done" },
    ]);
    const rm = fakeTool("rm", () => "deleted", "DESTRUCTIVE");
    registry.set("sess-1", makeSession(model, [rm], true));

    const { executor, sent, approvals } = makeExecutor({ wireApprovals: true });

    // Simulate the engine sending an approval.decision back.
    const approvalFramePromise = vi.waitFor(() => {
      const frame = sent.find((f) => f.type === MessageType.APPROVAL_REQUEST);
      if (!frame) throw new Error("approval.request not sent yet");
      return frame;
    }, { timeout: 5000 });
    const handlePromise = executor.handleAssign(
      makeAssignPayload({ activeToolNames: ["rm"], stream: false }),
    );

    const approvalFrame = await approvalFramePromise;
    expect(approvalFrame).toBeDefined();
    const clientRequestId = (approvalFrame!.payload as { clientRequestId: string }).clientRequestId;
    approvals!.resolve({
      conversationId: "req-1",
      clientRequestId,
      decision: "APPROVED",
    });

    await handlePromise;

    // The tool should have been executed (tool result present).
    const toolResult = sent.find((f) => f.type === MessageType.INFERENCE_TOOL_RESULT);
    expect(toolResult).toBeDefined();
    expect(toolResult!.payload).toMatchObject({ toolCallId: "c1", isError: false });
  });

  it("skips DESTRUCTIVE tool execution when approval is rejected", async () => {
    const model = new ScriptedModel([
      { content: "deleting", toolCalls: [toolCall("c1", "rm", { path: "/tmp/x" })] },
      { content: "I will not delete the file." },
    ]);
    const rm = fakeTool("rm", () => "deleted", "DESTRUCTIVE");
    // Track whether the tool's invoke was called.
    let invokeCalled = false;
    const rmTracked: SessionTool = {
      ...rm,
      invoke: async () => { invokeCalled = true; return "deleted"; },
    };
    registry.set("sess-1", makeSession(model, [rmTracked], true));

    const { executor, sent, approvals } = makeExecutor({ wireApprovals: true });

    const approvalFramePromise = vi.waitFor(() => {
      const frame = sent.find((f) => f.type === MessageType.APPROVAL_REQUEST);
      if (!frame) throw new Error("approval.request not sent yet");
      return frame;
    }, { timeout: 5000 });
    const handlePromise = executor.handleAssign(
      makeAssignPayload({ activeToolNames: ["rm"], stream: false }),
    );

    const approvalFrame = await approvalFramePromise;
    const clientRequestId = (approvalFrame!.payload as { clientRequestId: string }).clientRequestId;
    approvals!.resolve({
      conversationId: "req-1",
      clientRequestId,
      decision: "REJECTED",
      comment: "no way",
    });

    await handlePromise;

    // The tool should NOT have been executed.
    expect(invokeCalled).toBe(false);

    // The tool result should be an error with the rejection message.
    const toolResult = sent.find((f) => f.type === MessageType.INFERENCE_TOOL_RESULT);
    expect(toolResult).toBeDefined();
    expect(toolResult!.payload).toMatchObject({ toolCallId: "c1", isError: true });

    // The model should have gotten the rejection as a tool message and produced a final answer.
    const complete = sent.find((f) => f.type === MessageType.INFERENCE_COMPLETE);
    expect(complete).toBeDefined();
    expect(complete!.payload).toMatchObject({ content: "I will not delete the file." });
  });

  it("executes SAFE tools immediately without approval even when autoHitlOnDestructive=true", async () => {
    const model = new ScriptedModel([
      { content: "checking", toolCalls: [toolCall("c1", "search", { q: "test" })] },
      { content: "found it" },
    ]);
    const search = fakeTool("search", () => "result", "SAFE");
    registry.set("sess-1", makeSession(model, [search], true));

    const { executor, sent } = makeExecutor({ wireApprovals: true });

    await executor.handleAssign(
      makeAssignPayload({ activeToolNames: ["search"], stream: false }),
    );

    // No approval request should have been sent.
    const approvalFrame = sent.find((f) => f.type === MessageType.APPROVAL_REQUEST);
    expect(approvalFrame).toBeUndefined();

    // The tool should have been executed.
    const toolResult = sent.find((f) => f.type === MessageType.INFERENCE_TOOL_RESULT);
    expect(toolResult).toBeDefined();
    expect(toolResult!.payload).toMatchObject({ toolCallId: "c1", isError: false });
  });

  it("executes DESTRUCTIVE tools without approval when autoHitlOnDestructive=false", async () => {
    const model = new ScriptedModel([
      { content: "deleting", toolCalls: [toolCall("c1", "rm", { path: "/tmp/x" })] },
      { content: "done" },
    ]);
    const rm = fakeTool("rm", () => "deleted", "DESTRUCTIVE");
    registry.set("sess-1", makeSession(model, [rm], false));

    const { executor, sent } = makeExecutor({ wireApprovals: true });

    await executor.handleAssign(
      makeAssignPayload({ activeToolNames: ["rm"], stream: false }),
    );

    // No approval request should have been sent.
    const approvalFrame = sent.find((f) => f.type === MessageType.APPROVAL_REQUEST);
    expect(approvalFrame).toBeUndefined();

    // The tool should have been executed.
    const toolResult = sent.find((f) => f.type === MessageType.INFERENCE_TOOL_RESULT);
    expect(toolResult).toBeDefined();
    expect(toolResult!.payload).toMatchObject({ toolCallId: "c1", isError: false });
  });

  it("executes DESTRUCTIVE tools without approval when no ApprovalCoordinator is wired", async () => {
    const model = new ScriptedModel([
      { content: "deleting", toolCalls: [toolCall("c1", "rm", { path: "/tmp/x" })] },
      { content: "done" },
    ]);
    const rm = fakeTool("rm", () => "deleted", "DESTRUCTIVE");
    // autoHitlOnDestructive=true but no approvals coordinator wired
    registry.set("sess-1", makeSession(model, [rm], true));

    const { executor, sent } = makeExecutor();

    await executor.handleAssign(
      makeAssignPayload({ activeToolNames: ["rm"], stream: false }),
    );

    // No approval request should have been sent.
    const approvalFrame = sent.find((f) => f.type === MessageType.APPROVAL_REQUEST);
    expect(approvalFrame).toBeUndefined();

    // The tool should have been executed.
    const toolResult = sent.find((f) => f.type === MessageType.INFERENCE_TOOL_RESULT);
    expect(toolResult).toBeDefined();
    expect(toolResult!.payload).toMatchObject({ toolCallId: "c1", isError: false });
  });
});