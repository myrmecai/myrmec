// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { describe, it, expect, vi } from "vitest";
import { AgentSupervisor } from "./AgentSupervisor.js";
import type {
  HostControlClient,
  HostControlConnection,
} from "./HostControlClient.js";
import { MessageType, SUPPORTED_PROTOCOL_VERSION } from "../protocol/unifiedFrames.js";
import { makeEnvelope, type Envelope } from "../protocol/envelope.js";
import type {
  AuthContext,
  Logger,
  Task,
  WorkspaceHandle,
} from "../models/index.js";

const sessionId = "33333333-3333-4333-8333-333333333333";
const executionId = "44444444-4444-4444-8444-444444444444";
const hostInstanceId = "22222222-2222-4222-8222-222222222222";

/** Fake transport (same shape as the client-level test fake): records raw
 * outbound unified frames and lets tests drive inbound frames + close. */
class FakeHostControlConnection implements HostControlConnection {
  connected = false;
  connectToken?: string;
  readonly sent: string[] = [];
  onMessage?: (raw: string) => void | Promise<void>;
  onConnect?: () => void | Promise<void>;
  onDisconnect?: (code: number, reason: string) => void | Promise<void>;

  async connect(token: string): Promise<void> {
    this.connectToken = token;
    this.connected = true;
    await this.onConnect?.();
  }

  async disconnect(_reason?: string): Promise<void> {
    this.connected = false;
  }

  get isConnected(): boolean {
    return this.connected;
  }

  async send(raw: string): Promise<void> {
    this.sent.push(raw);
  }

  simulateInbound(frame: Record<string, unknown>): void | Promise<void> {
    return this.onMessage?.(JSON.stringify(frame));
  }

  simulateClose(code: number, reason: string): void | Promise<void> {
    this.connected = false;
    return this.onDisconnect?.(code, reason);
  }
}

/** A concrete Supervisor that stubs the abstract seams, injects the fake
 * connection, and captures the Agent-forwarded + engine-routed frames so
 * the unified routing surface can be asserted through the FULL composition
 * (supervisor → HostControlClient → fake socket). */
class TestSupervisor extends AgentSupervisor {
  readonly forwardedToWorker: { type: string; payload: unknown }[] = [];
  readonly sessionOpened: { sessionId: string }[] = [];
  readonly sessionClosed: string[] = [];
  readonly started: unknown[] = [];
  readonly cancelled: unknown[] = [];
  readonly connectionStates: Array<{
    sessionId: string;
    ready: boolean;
    fatal: boolean;
  }> = [];
  token = "tok-1";

  constructor(
    logger?: Logger,
    connection?: HostControlConnection,
    options: { ownerUserId?: string } = {},
  ) {
    super({
      engineUrl: "http://engine.local",
      role: "HEADLESS",
      ...(logger ? { logger } : {}),
      ...(connection ? { connection } : {}),
      ...(options.ownerUserId !== undefined
        ? { ownerUserId: options.ownerUserId }
        : {}),
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

  protected override forwardToWorker(frame: { type: string; payload: unknown }): void {
    this.forwardedToWorker.push({ type: frame.type, payload: frame.payload });
  }

  protected override onSessionOpen(payload: { sessionId: string }): void {
    this.sessionOpened.push(payload);
  }

  protected override onSessionClose(payload: { sessionId: string }): void {
    this.sessionClosed.push(payload.sessionId);
  }

  protected override onConnectionState(state: {
    sessionId: string;
    ready: boolean;
    fatal: boolean;
  }): void {
    this.connectionStates.push(state);
  }

  protected override async onExecutionStart(
    frame: Parameters<
      import("./HostControlClient.js").ExecutionHandler
    >[0],
  ): Promise<void> {
    this.started.push(frame);
  }

  protected override async onExecutionCancel(
    frame: Parameters<
      import("./HostControlClient.js").ExecutionCancelHandler
    >[0],
  ): Promise<void> {
    this.cancelled.push(frame);
  }

  // Public test hooks onto the protected routing surface.
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

describe("AgentSupervisor on the unified wire (composition root)", () => {
  function hostOpenedFrame(): Record<string, unknown> {
    // Unique messageId per frame — the client's dedupe LRU would otherwise
    // swallow a repeated host.opened across tests/reconnects.
    return {
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: `m-opened-${Math.random().toString(36).slice(2, 8)}`,
      type: MessageType.HOST_OPENED,
      sentAt: new Date().toISOString(),
      hostInstanceId,
      payload: {
        hostInstanceId,
        protocolVersion: SUPPORTED_PROTOCOL_VERSION,
        effectivePoolSize: 1,
        heartbeatIntervalSeconds: 60,
        offerTimeoutSeconds: 10,
        eventReplayWindowSeconds: 3600,
        streamLimits: {
          maxFrameBytes: 65536,
          maxBufferedDeltaBytesPerSession: 262144,
          maxUnacknowledgedEventBytesPerSession: 8388608,
          eventBackpressureTimeoutSeconds: 30,
        },
        serverNodeId: "node-a",
        // §22.2: the engine echoes the accepted negotiated capabilities.
        acceptedCapabilities: {
          sessionInteraction: { version: 1, temporaryHold: true },
        },
      },
    };
  }

  function sentTypes(sent: string[]): string[] {
    return sent.map((raw) => JSON.parse(raw).type as string);
  }

  it("opens the host-control socket through the composed client (host.open on connect, token from the auth seam)", async () => {
    const conn = new FakeHostControlConnection();
    const sup = new TestSupervisor(silentLogger, conn);
    await sup.start();

    expect(conn.connected).toBe(true);
    // The auth seam's token rode the handshake (HOST_JWT gate).
    expect(conn.connectToken).toBe("tok-1");
    const open = conn.sent
      .map((raw) => JSON.parse(raw))
      .find((f) => f.type === MessageType.HOST_OPEN);
    expect(open).toBeDefined();
    expect(open.payload.poolSize).toBe(1);
    // host.announce is GONE — capacity rides host.open.
    expect(sentTypes(conn.sent)).not.toContain("host.announce");
    expect(sup.isRunning).toBe(true);
    await sup.stop();
    expect(conn.connected).toBe(false);
  });

  it("forwards ownerUserId from supervisor options into the composed client's host.open (§3.7)", async () => {
    const ownerUserId = "88888888-8888-4888-8888-888888888888";
    const conn = new FakeHostControlConnection();
    const sup = new TestSupervisor(silentLogger, conn, { ownerUserId });
    await sup.start();

    const open = conn.sent
      .map((raw) => JSON.parse(raw))
      .find((f) => f.type === MessageType.HOST_OPEN);
    expect(open).toBeDefined();
    expect(open.payload.ownerUserId).toBe(ownerUserId);
    await sup.stop();
  });

  it("routes execution.start through the client to the overridable hook", async () => {
    const conn = new FakeHostControlConnection();
    const sup = new TestSupervisor(silentLogger, conn);
    await sup.start();
    await conn.simulateInbound(hostOpenedFrame());

    await conn.simulateInbound({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: "m-start",
      type: MessageType.EXECUTION_START,
      sentAt: new Date().toISOString(),
      sessionId,
      executionId,
      payload: {
        executionId,
        sessionId,
        sequenceNo: 1,
        deadline: new Date(Date.now() + 60_000).toISOString(),
      },
    });

    expect(sup.started).toHaveLength(1);
    expect((sup.started[0] as { type: string }).type).toBe(
      MessageType.EXECUTION_START,
    );
    await sup.stop();
  });

  it("routes execution.cancel through the client to the cancel hook", async () => {
    const conn = new FakeHostControlConnection();
    const sup = new TestSupervisor(silentLogger, conn);
    await sup.start();
    await conn.simulateInbound(hostOpenedFrame());

    await conn.simulateInbound({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: "m-cancel",
      type: MessageType.EXECUTION_CANCEL,
      sentAt: new Date().toISOString(),
      sessionId,
      executionId,
      payload: {
        executionId,
        dispatchId: executionId,
        reasonCode: "USER_REQUESTED",
        requestedAt: new Date().toISOString(),
        gracePeriodSeconds: 30,
      },
    });

    expect(sup.cancelled).toHaveLength(1);
    expect((sup.cancelled[0] as { type: string }).type).toBe(
      MessageType.EXECUTION_CANCEL,
    );
    await sup.stop();
  });

  it("mirrors session.open/session.close to the subclass hooks", async () => {
    const conn = new FakeHostControlConnection();
    const sup = new TestSupervisor(silentLogger, conn);
    await sup.start();
    await conn.simulateInbound(hostOpenedFrame());

    await conn.simulateInbound({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: "m-open",
      type: MessageType.SESSION_OPEN,
      sentAt: new Date().toISOString(),
      sessionId,
      payload: {
        sessionId,
        kind: "CONVERSATION",
        projectId: "11111111-1111-4111-8111-111111111111",
        profileVersionId: "22222222-2222-4222-8222-222222222222",
        model: { provider: "openai", modelId: "gpt-4", parameters: {} },
        tools: [],
        knowledgeSources: [],
        autoHitlOnDestructive: true,
      },
    });
    await conn.simulateInbound({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: "m-close",
      type: MessageType.SESSION_CLOSE,
      sentAt: new Date().toISOString(),
      sessionId,
      payload: { sessionId, reasonCode: "CONVERSATION_ARCHIVED", gracePeriodSeconds: 30 },
    });

    expect(sup.sessionOpened).toHaveLength(1);
    expect(sup.sessionOpened[0].sessionId).toBe(sessionId);
    expect(sup.sessionClosed).toEqual([sessionId]);
    // The transport replies were sent (opened + closed).
    const types = sentTypes(conn.sent);
    expect(types).toContain(MessageType.SESSION_OPENED);
    expect(types).toContain(MessageType.SESSION_CLOSED);
    await sup.stop();
  });

  it("wires the client's connection-state seam through to the overridable hook (§22.8 D7)", async () => {
    const conn = new FakeHostControlConnection();
    const sup = new TestSupervisor(silentLogger, conn);
    await sup.start();

    // The composition contract: buildClient() registered
    // `onConnectionState: (state) => this.onConnectionState(state)` on the
    // client. Invoke THAT registered closure (the client emits it on
    // channel loss / socket drop; the private field is the exact wiring
    // under test, mirroring the suite's private-access conventions).
    const client = (sup as unknown as {
      client: {
        onConnectionState:
          | ((state: { sessionId: string; ready: boolean; fatal: boolean }) => void)
          | null;
      };
    }).client;
    expect(client.onConnectionState).toBeTypeOf("function");
    client.onConnectionState!({ sessionId, ready: false, fatal: true });
    client.onConnectionState!({ sessionId, ready: true, fatal: false });

    expect(sup.connectionStates).toEqual([
      { sessionId, ready: false, fatal: true },
      { sessionId, ready: true, fatal: false },
    ]);
    await sup.stop();
  });

  it("routes Agent execution frames through the client's typed senders", async () => {
    const conn = new FakeHostControlConnection();
    const sup = new TestSupervisor(silentLogger, conn);
    await sup.start();
    await conn.simulateInbound(hostOpenedFrame());
    conn.sent.length = 0;

    // The Agent's legacy-shape frames, mapped by the supervisor's post().
    await sup.route(
      makeEnvelope("execution.delta", {
        executionId,
        index: 0,
        content: "hi",
        contentType: "TEXT",
      }),
    );
    await sup.route(
      makeEnvelope("execution.complete", {
        executionId,
        completedAt: new Date().toISOString(),
      }),
    );

    expect(sentTypes(conn.sent)).toEqual([
      MessageType.EXECUTION_DELTA,
      MessageType.EXECUTION_COMPLETE,
    ]);
    await sup.stop();
  });

  it("routes the §22 interaction-family frames through post() to the typed senders", async () => {
    const conn = new FakeHostControlConnection();
    const sup = new TestSupervisor(silentLogger, conn);
    await sup.start();
    await conn.simulateInbound(hostOpenedFrame());
    conn.sent.length = 0;

    const interactionId = "66666666-6666-4666-8666-666666666666";
    const dispatchId = "55555555-5555-4555-8555-555555555555";
    const controlRequestId = "77777777-7777-4777-8777-777777777777";
    const completedAt = new Date().toISOString();
    // §22.3 observation point: the composed client's senders are the mapping
    // under test here (the wire-level dedicated-only routing is the client
    // suite's subject). Invoke the protected post() and observe through the
    // client's sender seam by replacing it post-construction - the exact
    // private-access convention this suite already uses.
    const client = (sup as unknown as { client: HostControlClient }).client;
    const sent: Array<{ type: string; messageId?: string; correlationId?: string | null }> = [];
    client.sendExecutionControlState = async (payload) => {
      sent.push({
        type: "execution.control.state",
        messageId: (payload as { messageIdOverride?: string }).messageIdOverride,
        correlationId: (payload as { correlationIdOverride?: string }).correlationIdOverride ?? null,
      });
    };
    client.sendExecutionInteractionDelta = async () => {
      sent.push({ type: "execution.interaction.delta" });
    };
    client.sendExecutionInteractionComplete = async (payload) => {
      sent.push({
        type: "execution.interaction.complete",
        messageId: (payload as { messageIdOverride?: string }).messageIdOverride,
      });
    };
    client.sendExecutionInteractionFailed = async (payload) => {
      sent.push({
        type: "execution.interaction.failed",
        messageId: (payload as { messageIdOverride?: string }).messageIdOverride,
      });
    };
    client.sendExecutionControlRequest = async (payload) => {
      sent.push({
        type: "execution.control.request",
        messageId: (payload as { messageIdOverride?: string }).messageIdOverride,
      });
    };

    // The worker bridge's Envelope shapes for the §22.3 family (the durable
    // four carry their outbox record identity via messageIdOverride).
    await sup.route(
      makeEnvelope("execution.control.state", {
        executionId,
        dispatchId,
        controlRevision: 1,
        stateSequence: 2,
        status: "HELD",
        effectiveState: "HELD",
        reasonCode: "USER_REQUESTED",
        changedAt: completedAt,
        messageIdOverride: "ctl-x-2",
        correlationIdOverride: "m-cmd",
      }),
    );
    await sup.route(
      makeEnvelope("execution.interaction.delta", {
        executionId,
        dispatchId,
        interactionId,
        index: 0,
        text: "frag",
      }),
    );
    await sup.route(
      makeEnvelope("execution.interaction.complete", {
        executionId,
        dispatchId,
        interactionId,
        ordinal: 1,
        answer: { text: "done" },
        usage: null,
        usageStatus: "UNKNOWN",
        controlRequestIds: [],
        completedAt,
        messageIdOverride: "interaction-1-complete",
      }),
    );
    await sup.route(
      makeEnvelope("execution.interaction.failed", {
        executionId,
        dispatchId,
        interactionId,
        ordinal: 1,
        error: { errorCode: "MODEL_ERROR", message: "fail", retryable: false },
        usage: null,
        usageStatus: "UNKNOWN",
        controlRequestIds: [],
        completedAt,
        messageIdOverride: "interaction-1-failed",
      }),
    );
    await sup.route(
      makeEnvelope("execution.control.request", {
        executionId,
        dispatchId,
        interactionId,
        controlRequestId,
        action: "CANCEL",
        explanation: "User asked to stop this attempt.",
        messageIdOverride: "cr-1",
      }),
    );

    expect(sent.map((f) => f.type)).toEqual([
      "execution.control.state",
      "execution.interaction.delta",
      "execution.interaction.complete",
      "execution.interaction.failed",
      "execution.control.request",
    ]);
    // The §12.1 identity overrides/§22.4 correlation survived the post()
    // mapping intact (the supervisor maps the envelope verbatim).
    const stateFrame = sent[0] as { messageId?: string; correlationId?: string | null };
    expect(stateFrame.messageId).toBe("ctl-x-2");
    expect(stateFrame.correlationId).toBe("m-cmd");
    expect(sent[2].messageId).toBe("interaction-1-complete");
    expect(sent[3].messageId).toBe("interaction-1-failed");
    expect(sent[4].messageId).toBe("cr-1");
    // No unknown-frame warnings for the family.
    expect(silentLogger.warn).not.toHaveBeenCalledWith(
      "Unknown Agent frame type - dropped:",
      expect.any(String),
    );
    await sup.stop();
  });

  it("drops an unknown Agent frame type with a warning (legacy wire deleted)", async () => {
    const conn = new FakeHostControlConnection();
    const sup = new TestSupervisor(silentLogger, conn);
    await sup.start();
    await conn.simulateInbound(hostOpenedFrame());
    conn.sent.length = 0;

    await sup.route(
      makeEnvelope("message.delta", { conversationId: "c1", content: "hi" }),
    );

    expect(conn.sent).toHaveLength(0);
    expect(silentLogger.warn).toHaveBeenCalled();
    await sup.stop();
  });

  it("warns on unknown inbound frame types without crashing", async () => {
    const conn = new FakeHostControlConnection();
    const sup = new TestSupervisor(silentLogger, conn);
    await sup.start();
    await conn.simulateInbound(hostOpenedFrame());

    await conn.simulateInbound({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: "m-junk",
      type: "nonsense.frame",
      sentAt: new Date().toISOString(),
      payload: { foo: 1 },
    });

    expect(sup.started).toHaveLength(0);
    expect(silentLogger.warn).toHaveBeenCalled();
    await sup.stop();
  });
});