// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { describe, it, expect, vi } from "vitest";
import { AgentWorker } from "./agentWorker.js";
import type { WorkerInbound, WorkerOutbound } from "./agentWorkerProtocol.js";
import { MessageType } from "../protocol/messages.js";
import { MessageType as UnifiedMessageType } from "../protocol/unifiedFrames.js";
import type { ChatModelFactory, SessionToolFactory } from "../executor/providers.js";
import type { ChatModel, ModelStreamChunk } from "../executor/types.js";

/** Streaming model that yields fixed chunks for worker-level tests. */
class FixedStreamModel implements ChatModel {
  constructor(private readonly chunks: ModelStreamChunk[]) {}
  async invoke(): Promise<{ content: string }> {
    throw new Error("worker test expects streaming path");
  }
  async *stream(): AsyncIterable<ModelStreamChunk> {
    for (const c of this.chunks) yield c;
  }
}

/** Factory that always returns a streaming model with chunks "Hel", "lo". */
const streamingChatModelFactory: ChatModelFactory = {
  resolve: async () => new FixedStreamModel([{ content: "Hel" }, { content: "lo" }]),
};

/** No-op factories for tests that don't exercise model/tool resolution. */
const noopChatModelFactory: ChatModelFactory = {
  resolve: async () => { throw new Error("test does not exercise model resolution") },
};
const noopSessionToolFactory: SessionToolFactory = {
  resolve: async () => new Map(),
};

function inbound(type: string, payload: unknown): WorkerInbound {
  return {
    kind: "envelope",
    frame: { type, timestamp: new Date().toISOString(), payload },
  };
}

function sink() {
  const messages: WorkerOutbound[] = [];
  const post = (m: WorkerOutbound) => messages.push(m);
  const frames = () =>
    messages
      .filter((m) => m.kind === "frame")
      .map((m) => m.frame as { type: string; payload: unknown });
  const typesSent = () => frames().map((f) => f.type);
  return { post, frames, typesSent };
}

const SESSION_ID = "33333333-3333-4333-8333-333333333333";
const EXECUTION_ID = "44444444-4444-4444-8444-444444444444";

function sessionOpenPayload() {
  return {
    sessionId: SESSION_ID,
    serviceType: "CONVERSATION",
    projectId: "22222222-2222-2222-8222-222222222222",
    profileVersionId: "11111111-1111-4111-8111-111111111111",
    model: { provider: "openai", modelId: "gpt-4o" },
    tools: [],
    knowledgeSources: [],
    autoHitlOnDestructive: false,
  };
}

function executionStartPayload(stream = true) {
  return {
    executionId: EXECUTION_ID,
    sessionId: SESSION_ID,
    sequenceNo: 1,
    requestId: "req-1",
    deadline: new Date(Date.now() + 300_000).toISOString(),
    input: {
      messages: [{ role: "user", content: "hello", parts: null, toolCalls: null }],
      attachments: null,
      conversationContinuation: null,
    },
    toolPolicy: { activeToolNames: [], approvalMode: null },
    output: { stream, responseSequenceNo: 1, format: "TEXT" },
  };
}

describe("AgentWorker", () => {
  it("routes a unified execution.start (after session.open) and posts execution.delta + execution.complete", async () => {
    const { post, frames, typesSent } = sink();
    const worker = new AgentWorker({
      post,
      chatModelFactory: streamingChatModelFactory,
      sessionToolFactory: noopSessionToolFactory,
    });

    // Session must be opened before execution.start can resolve the model.
    await worker.handle(inbound(MessageType.SESSION_OPEN, sessionOpenPayload()));

    worker.handle(inbound(UnifiedMessageType.EXECUTION_START, executionStartPayload(true)));

    await vi.waitFor(() =>
      expect(typesSent()).toContain(UnifiedMessageType.EXECUTION_COMPLETE),
    );

    const deltas = frames().filter((f) => f.type === UnifiedMessageType.EXECUTION_DELTA);
    expect(deltas.map((f) => (f.payload as { content: string }).content)).toEqual(
      ["Hel", "lo"],
    );
    expect(
      frames().find((f) => f.type === UnifiedMessageType.EXECUTION_COMPLETE)?.payload,
    ).toMatchObject({ executionId: EXECUTION_ID, result: { content: "Hello" } });
  });

  it("ignores an unhandled frame type without emitting", () => {
    const { post, frames } = sink();
    const warn = vi.fn();
    const worker = new AgentWorker({
      post,
      chatModelFactory: noopChatModelFactory,
      sessionToolFactory: noopSessionToolFactory,
      logger: { debug: vi.fn(), info: vi.fn(), warn, error: vi.fn() },
    });

    worker.handle(inbound("something.unknown", {}));

    expect(frames()).toHaveLength(0);
    expect(warn).toHaveBeenCalled();
  });

  it("validates and records an agent.bind without emitting", () => {
    const { post, frames } = sink();
    const debug = vi.fn();
    const warn = vi.fn();
    const worker = new AgentWorker({
      post,
      chatModelFactory: noopChatModelFactory,
      sessionToolFactory: noopSessionToolFactory,
      logger: { debug, info: vi.fn(), warn, error: vi.fn() },
    });

    worker.handle(
      inbound(MessageType.AGENT_BIND, {
        conversationId: "c1",
        profileVersionId: "pv1",
      }),
    );

    expect(frames()).toHaveLength(0);
    expect(warn).not.toHaveBeenCalled();
    expect(debug).toHaveBeenCalled();
  });

  it("warns on an invalid agent.bind payload", () => {
    const { post } = sink();
    const warn = vi.fn();
    const worker = new AgentWorker({
      post,
      chatModelFactory: noopChatModelFactory,
      sessionToolFactory: noopSessionToolFactory,
      logger: { debug: vi.fn(), info: vi.fn(), warn, error: vi.fn() },
    });

    worker.handle(inbound(MessageType.AGENT_BIND, { conversationId: "c1" }));

    expect(warn).toHaveBeenCalled();
  });

  it("validates an agent.release without emitting", () => {
    const { post, frames } = sink();
    const warn = vi.fn();
    const worker = new AgentWorker({
      post,
      chatModelFactory: noopChatModelFactory,
      sessionToolFactory: noopSessionToolFactory,
      logger: { debug: vi.fn(), info: vi.fn(), warn, error: vi.fn() },
    });

    worker.handle(
      inbound(MessageType.AGENT_RELEASE, {
        conversationId: "c1",
        reason: "turn complete",
      }),
    );

    expect(frames()).toHaveLength(0);
    expect(warn).not.toHaveBeenCalled();
  });

  it("ignores a non-envelope inbound message", () => {
    const { post, frames } = sink();
    const warn = vi.fn();
    const worker = new AgentWorker({
      post,
      chatModelFactory: noopChatModelFactory,
      sessionToolFactory: noopSessionToolFactory,
      logger: { debug: vi.fn(), info: vi.fn(), warn, error: vi.fn() },
    });

    // @ts-expect-error exercising the runtime guard for an unknown kind
    worker.handle({ kind: "bogus" });

    expect(frames()).toHaveLength(0);
    expect(warn).toHaveBeenCalled();
  });

  it("constructs with the built-in resolver when none is injected", () => {
    const { post } = sink();
    expect(() => new AgentWorker({ post, chatModelFactory: noopChatModelFactory, sessionToolFactory: noopSessionToolFactory })).not.toThrow();
  });
});
