// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { describe, it, expect, vi } from "vitest";
import { AgentWorker } from "./agentWorker.js";
import type { WorkerInbound, WorkerOutbound } from "./agentWorkerProtocol.js";
import { MessageType } from "../protocol/messages.js";
import type { ChatModelFactory, SessionToolFactory } from "../executor/providers.js";

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
    messages.filter((m) => m.kind === "frame").map((m) => m.frame);
  const typesSent = () => frames().map((f) => f.type);
  return { post, frames, typesSent };
}

function turnPayload(overrides: Record<string, unknown> = {}) {
  return {
    conversationId: "c1",
    projectId: "p1",
    agentId: "a1",
    assistantSequenceNo: 5,
    userMessage: "hello",
    model: { provider: "openai", modelId: "gpt-4o" },
    ...overrides,
  };
}

describe("AgentWorker", () => {
  it("routes a conversation.turn.assign and posts deltas + complete out", async () => {
    const { post, frames, typesSent } = sink();
    const worker = new AgentWorker({ post, chatModelFactory: noopChatModelFactory, sessionToolFactory: noopSessionToolFactory });

    worker.handle(
      inbound(MessageType.CONVERSATION_TURN_ASSIGN, turnPayload()),
    );

    await vi.waitFor(() =>
      expect(typesSent()).toContain(MessageType.MESSAGE_COMPLETE),
    );

    const deltas = frames().filter((f) => f.type === MessageType.MESSAGE_DELTA);
    expect(deltas.map((f) => (f.payload as { content: string }).content)).toEqual(
      ["Hel", "lo"],
    );
    expect(
      frames().find((f) => f.type === MessageType.MESSAGE_COMPLETE)?.payload,
    ).toMatchObject({ conversationId: "c1", content: "Hello" });
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
