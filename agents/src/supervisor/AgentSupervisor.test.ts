// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { describe, it, expect, vi } from "vitest";
import { AgentSupervisor } from "./AgentSupervisor.js";
import { MessageType } from "../protocol/messages.js";
import { makeEnvelope, type Envelope, type RawEnvelope } from "../protocol/envelope.js";
import type {
  AuthContext,
  Logger,
  Task,
  WorkspaceHandle,
} from "../models/index.js";

/** A concrete Supervisor that stubs the abstract seams and captures the
 * control-socket sends and the worker-forwarded frames so the unified
 * routing surface can be asserted in isolation (P6-T6: the conversation
 * socket and bind hooks are deleted; everything rides the control socket). */
class TestSupervisor extends AgentSupervisor {
  readonly forwardedToWorker: RawEnvelope[] = [];
  readonly controlSent: Envelope[] = [];
  token = "tok-1";

  constructor(logger?: Logger) {
    super({
      engineUrl: "http://engine.local",
      role: "HEADLESS",
      ...(logger ? { logger } : {}),
    });
    this.ctx.agentId = "agent-1";
  }

  protected authenticate(): Promise<AuthContext> {
    return Promise.resolve({
      agentAccessToken: this.token,
      agentRefreshToken: "r",
      agentTokenExpiresAt: 0,
    });
  }
  protected getAccessToken(): Promise<string> {
    return Promise.resolve(this.token);
  }
  protected refreshTokens(): Promise<string> {
    return Promise.resolve(this.token);
  }
  protected reRegister(): Promise<string> {
    return Promise.resolve(this.token);
  }
  protected resolveWorkspace(_task: Task): Promise<WorkspaceHandle> {
    throw new Error("not needed");
  }

  protected forwardToWorker(frame: RawEnvelope): void {
    this.forwardedToWorker.push(frame);
  }

  protected send(frame: Envelope): Promise<void> {
    this.controlSent.push(frame);
    return Promise.resolve();
  }

  // Public test hooks onto the protected dispatch surface.
  envelope(frame: RawEnvelope): Promise<void> {
    return this.onEnvelope(frame);
  }
  route(frame: Envelope): Promise<void> {
    return this.routeWorkerFrame(frame);
  }
}

const silentLogger: Logger = {
  debug: vi.fn(),
  info: vi.fn(),
  warn: vi.fn(),
  error: vi.fn(),
};

describe("AgentSupervisor unified routing (P6-T6)", () => {
  it("forwards unified session/inference frames to the worker", async () => {
    const sup = new TestSupervisor(silentLogger);
    for (const type of [
      MessageType.SESSION_OPEN,
      MessageType.SESSION_CLOSE,
      MessageType.INFERENCE_ASSIGN,
      MessageType.INFERENCE_CANCEL,
      MessageType.ORCHESTRATION_RELEASE,
      MessageType.ORCHESTRATION_BUDGET_UPDATED,
    ]) {
      await sup.envelope(makeEnvelope(type, { sessionId: "s1" }) as RawEnvelope);
    }
    expect(sup.forwardedToWorker.map((f) => f.type)).toEqual([
      MessageType.SESSION_OPEN,
      MessageType.SESSION_CLOSE,
      MessageType.INFERENCE_ASSIGN,
      MessageType.INFERENCE_CANCEL,
      MessageType.ORCHESTRATION_RELEASE,
      MessageType.ORCHESTRATION_BUDGET_UPDATED,
    ]);
    // Nothing leaked to the control socket from inbound frames.
    expect(sup.controlSent).toHaveLength(0);
  });

  it("routes every worker frame over the control socket (no conversation split)", async () => {
    const sup = new TestSupervisor(silentLogger);
    await sup.route(
      makeEnvelope(MessageType.MESSAGE_DELTA, {
        conversationId: "c1",
        sequenceNo: 1,
        deltaIndex: 0,
        content: "hi",
      }),
    );
    await sup.route(
      makeEnvelope(MessageType.SESSION_OPEN, { sessionId: "s1" }),
    );
    expect(sup.controlSent.map((f) => f.type)).toEqual([
      MessageType.MESSAGE_DELTA,
      MessageType.SESSION_OPEN,
    ]);
  });

  it("warns on unknown frame types without crashing", async () => {
    const sup = new TestSupervisor(silentLogger);
    await sup.envelope(
      makeEnvelope("nonsense.frame", { foo: 1 }) as RawEnvelope,
    );
    expect(sup.forwardedToWorker).toHaveLength(0);
    expect(silentLogger.warn).toHaveBeenCalled();
  });
});