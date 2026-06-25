// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { describe, it, expect, vi } from "vitest";
import { AgentWorkerHost, type WorkerLike } from "./AgentWorkerHost.js";
import type { WorkerOutbound } from "./agentWorkerProtocol.js";
import type { Envelope, RawEnvelope } from "../protocol/envelope.js";
import { makeEnvelope } from "../protocol/envelope.js";
import { MessageType } from "../protocol/messages.js";

/** A fake worker that lets the test drive message/error/exit and observe sends. */
class FakeWorker implements WorkerLike {
  posted: unknown[] = [];
  terminated = false;
  private listeners: Record<string, ((arg: never) => void)[]> = {
    message: [],
    error: [],
    exit: [],
  };

  postMessage(value: unknown): void {
    this.posted.push(value);
  }

  on(event: "message" | "error" | "exit", listener: (arg: never) => void): this {
    this.listeners[event].push(listener);
    return this;
  }

  terminate(): Promise<number> {
    this.terminated = true;
    return Promise.resolve(0);
  }

  emit(event: "message" | "error" | "exit", arg: unknown): void {
    for (const l of this.listeners[event]) {
      (l as (a: unknown) => void)(arg);
    }
  }
}

function frame(type: string): RawEnvelope {
  return { type, timestamp: new Date().toISOString(), payload: { x: 1 } };
}

describe("AgentWorkerHost", () => {
  it("forwards an inbound frame into the worker as an envelope message", () => {
    const worker = new FakeWorker();
    const host = new AgentWorkerHost({ worker, onFrame: () => {} });

    host.dispatch(frame(MessageType.TASK_ASSIGN));

    expect(worker.posted).toHaveLength(1);
    expect(worker.posted[0]).toMatchObject({
      kind: "envelope",
      frame: { type: MessageType.TASK_ASSIGN },
    });
  });

  it("routes a worker frame message out through onFrame", () => {
    const worker = new FakeWorker();
    const routed: Envelope[] = [];
    new AgentWorkerHost({ worker, onFrame: (f) => void routed.push(f) });

    const out: WorkerOutbound = {
      kind: "frame",
      frame: makeEnvelope(MessageType.MESSAGE_DELTA, { content: "hi" }),
    };
    worker.emit("message", out);

    expect(routed).toHaveLength(1);
    expect(routed[0].type).toBe(MessageType.MESSAGE_DELTA);
  });

  it("ignores an unknown worker message", () => {
    const worker = new FakeWorker();
    const routed: Envelope[] = [];
    const warn = vi.fn();
    new AgentWorkerHost({
      worker,
      onFrame: (f) => void routed.push(f),
      logger: { debug: vi.fn(), info: vi.fn(), warn, error: vi.fn() },
    });

    worker.emit("message", { kind: "mystery" });

    expect(routed).toHaveLength(0);
    expect(warn).toHaveBeenCalled();
  });

  it("contains a worker error and reports an exit", () => {
    const worker = new FakeWorker();
    const error = vi.fn();
    const onExit = vi.fn();
    new AgentWorkerHost({
      worker,
      onFrame: () => {},
      onExit,
      logger: { debug: vi.fn(), info: vi.fn(), warn: vi.fn(), error },
    });

    worker.emit("error", new Error("boom"));
    worker.emit("exit", 1);

    expect(error).toHaveBeenCalled();
    expect(onExit).toHaveBeenCalledWith(1);
  });

  it("terminates the worker on stop", async () => {
    const worker = new FakeWorker();
    const host = new AgentWorkerHost({ worker, onFrame: () => {} });

    await host.stop();

    expect(worker.terminated).toBe(true);
  });
});
