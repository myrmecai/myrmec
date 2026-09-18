// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import {
  HostControlClient,
  type ChannelConnection,
  type HostControlConnection,
  type HostSessionLifecycle,
  type HostTokenProvider,
} from "./HostControlClient.js";
import { MessageType, SUPPORTED_PROTOCOL_VERSION } from "../protocol/unifiedFrames.js";
import type { Logger } from "../models/index.js";

const silentLogger: Logger = {
  debug: vi.fn(),
  info: vi.fn(),
  warn: vi.fn(),
  error: vi.fn(),
};

const sessionId = "33333333-3333-4333-8333-333333333333";
const executionId = "44444444-4444-4444-8444-444444444444";
const hostInstanceId = "22222222-2222-4222-8222-222222222222";

/** Fake transport that records raw outbound unified frames and lets tests
 * drive inbound frames + close events. */
class FakeHostControlConnection implements HostControlConnection {
  connected = false;
  connectToken?: string;
  failOpen = false;
  readonly sent: string[] = [];
  onMessage?: (raw: string) => void | Promise<void>;
  onConnect?: () => void | Promise<void>;
  onDisconnect?: (code: number, reason: string) => void | Promise<void>;

  async connect(token: string): Promise<void> {
    if (this.failOpen) {
      throw new Error("dial failed");
    }
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

function makeTokenProvider(): HostTokenProvider {
  return {
    getAccessToken: vi.fn(async () => "host-token"),
    refreshTokens: vi.fn(async () => "host-refreshed"),
    reRegister: vi.fn(async () => "host-reregistered"),
  };
}

/** Fake channel socket that records raw outbound frames and lets tests
 * drive inbound frames + close events (mirrors FakeHostControlConnection). */
class FakeChannelConnection implements ChannelConnection {
  connected = false;
  readonly sent: string[] = [];
  onMessage?: (raw: string) => void | Promise<void>;
  onClose?: (code: number, reason: string) => void | Promise<void>;

  async connect(token: string): Promise<void> {
    this.connectToken = token;
    this.connected = true;
  }

  connectToken?: string;

  async close(reason?: string): Promise<void> {
    this.connected = false;
    this.closedWithReason = reason;
  }

  closedWithReason?: string;

  get isOpen(): boolean {
    return this.connected;
  }

  async send(raw: string): Promise<void> {
    if (!this.connected) {
      throw new Error("Channel not connected");
    }
    this.sent.push(raw);
  }

  simulateInbound(frame: Record<string, unknown>): void | Promise<void> {
    return this.onMessage?.(JSON.stringify(frame));
  }

  simulateClose(code: number, reason: string): void | Promise<void> {
    this.connected = false;
    return this.onClose?.(code, reason);
  }
}

/** Fake lifecycle collaborator (mirrors SessionRegistry). */
class FakeSessionLifecycle implements HostSessionLifecycle {
  readonly channels = new Map<
    string,
    { socket: unknown; alive: boolean; highestContiguousSequence: number }
  >();
  cursor = 0;

  getHighestContiguousSequence(sessionId: string): number {
    return this.channels.get(sessionId)?.highestContiguousSequence ?? this.cursor;
  }

  observeDurableSequence(sessionId: string, sequence: number): void {
    this.cursor = Math.max(this.cursor, sequence);
  }

  bindChannel(
    sessionId: string,
    socket: unknown,
    opened: { highestContiguousSequence: number },
  ): void {
    this.channels.set(sessionId, {
      socket,
      alive: true,
      highestContiguousSequence: opened.highestContiguousSequence,
    });
  }

  markChannelDead(sessionId: string): void {
    const ch = this.channels.get(sessionId);
    if (ch) {
      ch.alive = false;
    }
  }

  unbindChannel(sessionId: string): { socket: unknown } | null {
    const ch = this.channels.get(sessionId);
    this.channels.delete(sessionId);
    return ch ? { socket: ch.socket } : null;
  }
}

interface TestSubject {
  client: HostControlClient;
  conn: FakeHostControlConnection;
  tokens: HostTokenProvider;
  lifecycle: FakeSessionLifecycle;
  channelConns: FakeChannelConnection[];
}

function makeSubject(options: {
  failOpen?: boolean;
  poolSize?: number;
} = {}): TestSubject {
  const conn = new FakeHostControlConnection();
  conn.failOpen = options.failOpen ?? false;
  const tokens = makeTokenProvider();
  const lifecycle = new FakeSessionLifecycle();
  const channelConns: FakeChannelConnection[] = [];
  const client = new HostControlClient({
    engineUrl: "http://engine.local",
    tokenProvider: tokens,
    logger: silentLogger,
    connection: conn,
    poolSize: options.poolSize ?? 4,
    runtimeVersion: "1.8.0",
    sessionLifecycle: lifecycle,
    channelConnectionFactory: () => {
      const c = new FakeChannelConnection();
      channelConns.push(c);
      return c;
    },
  });
  return { client, conn, tokens, lifecycle, channelConns };
}

/** session.open payload builder with an optional channel offer. */
function sessionOpenPayload(
  overrides: { channel?: unknown; orchestration?: unknown } = {},
): Record<string, unknown> {
  const payload: Record<string, unknown> = {
    sessionId,
    kind: "CONVERSATION",
    projectId: "11111111-1111-4111-8111-111111111111",
    profileVersionId: "22222222-2222-4222-8222-222222222222",
    model: { provider: "openai", modelId: "gpt-4", parameters: {} },
    tools: [],
    knowledgeSources: [],
    autoHitlOnDestructive: true,
  };
  if (overrides.channel !== undefined) {
    payload.channel = overrides.channel;
  }
  return payload;
}

function simulateSessionOpen(
  conn: FakeHostControlConnection,
  payload: Record<string, unknown>,
): void | Promise<void> {
  return conn.simulateInbound({
    protocolVersion: SUPPORTED_PROTOCOL_VERSION,
    messageId: "m-open",
    type: MessageType.SESSION_OPEN,
    sentAt: new Date().toISOString(),
    sessionId,
    payload,
  });
}

function parseFirstByType(sent: string[], type: (typeof MessageType)[keyof typeof MessageType]) {
  const found = sent
    .map((raw) => JSON.parse(raw))
    .find((f) => f.type === type);
  return found;
}

function hostOpenedFrame(intervalSeconds: number): Record<string, unknown> {
  return {
    protocolVersion: SUPPORTED_PROTOCOL_VERSION,
    messageId: "m-opened",
    type: MessageType.HOST_OPENED,
    sentAt: new Date().toISOString(),
    hostInstanceId,
    payload: {
      hostInstanceId,
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      effectivePoolSize: 4,
      heartbeatIntervalSeconds: intervalSeconds,
      offerTimeoutSeconds: 10,
      eventReplayWindowSeconds: 3600,
      streamLimits: {
        maxFrameBytes: 65536,
        maxBufferedDeltaBytesPerSession: 262144,
        maxUnacknowledgedEventBytesPerSession: 8388608,
        eventBackpressureTimeoutSeconds: 30,
      },
      serverNodeId: "node-a",
    },
  };
}

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
});

afterEach(() => {
  vi.useRealTimers();
  vi.clearAllMocks();
});

describe("HostControlClient lifecycle", () => {
  it("sends host.open with fresh nonce on connect", async () => {
    const { client, conn } = makeSubject();

    await client.start();

    expect(conn.connected).toBe(true);
    const open = parseFirstByType(conn.sent, MessageType.HOST_OPEN);
    expect(open).toBeDefined();
    expect(open.payload.instanceNonce).toBe(client.currentNonce);
    expect(open.payload.hostname).toBeDefined();
    expect(open.payload.runtimeVersion).toBe("1.8.0");
    expect(open.payload.supportedProtocolVersions).toEqual([1]);
    expect(open.payload.poolSize).toBe(4);
    expect(client.currentState).toBe("CONNECTING");
  });

  it("transitions to OPEN on host.opened and emits heartbeat at interval", async () => {
    const { client, conn } = makeSubject();
    await client.start();

    await conn.simulateInbound(hostOpenedFrame(2));

    expect(client.currentState).toBe("OPEN");
    expect(client.currentHostInstanceId).toBe(hostInstanceId);

    await vi.advanceTimersByTimeAsync(2100);

    const heartbeats = conn.sent
      .map((raw) => JSON.parse(raw))
      .filter((f) => f.type === MessageType.HOST_HEARTBEAT);
    expect(heartbeats.length).toBeGreaterThanOrEqual(1);
    expect(heartbeats[0].payload.health).toBe("HEALTHY");
    expect(heartbeats[0].payload.effectivePoolSize).toBe(4);
  });

  it("reconnects with a fresh nonce after an abnormal close", async () => {
    const { client, conn } = makeSubject();
    await client.start();
    await conn.simulateInbound(hostOpenedFrame(60));
    const firstNonce = client.currentNonce;

    // A transport-level drop (not NORMAL) should trigger reconnect.
    await conn.simulateClose(1006, "abnormal");

    // connectWithRetry sleeps via real timers, but fake timers let us advance.
    await vi.advanceTimersByTimeAsync(2000);

    const opens = conn.sent
      .map((raw) => JSON.parse(raw))
      .filter((f) => f.type === MessageType.HOST_OPEN);
    expect(opens.length).toBeGreaterThanOrEqual(2);
    const secondNonce = opens[opens.length - 1].payload.instanceNonce;
    expect(secondNonce).not.toBe(firstNonce);
    expect(secondNonce).toBe(client.currentNonce);
  });
});

describe("HostControlClient session arms", () => {
  it("auto-accepts a session.offer with session.accept", async () => {
    const { client, conn } = makeSubject();
    await client.start();
    await conn.simulateInbound(hostOpenedFrame(60));

    await conn.simulateInbound({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: "m-offer",
      type: MessageType.SESSION_OFFER,
      sentAt: new Date().toISOString(),
      sessionId,
      payload: {
        allocationId: sessionId,
        sessionId,
        kind: "CONVERSATION",
        ref: { type: "CONVERSATION", id: sessionId },
        requirements: { tools: [], runtimes: [], features: [] },
        lease: { offerExpiresAt: new Date().toISOString(), idleTimeoutSeconds: 1800 },
        routing: { homeNodeId: "node-a" },
      },
    });

    const accept = parseFirstByType(conn.sent, MessageType.SESSION_ACCEPT);
    expect(accept).toBeDefined();
    expect(accept.payload.sessionId).toBe(sessionId);
    expect(accept.payload.acceptedAt).toBeDefined();
  });

  it("replies session.opened to a session.open", async () => {
    const { client, conn } = makeSubject();
    await client.start();
    await conn.simulateInbound(hostOpenedFrame(60));

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

    const opened = parseFirstByType(conn.sent, MessageType.SESSION_OPENED);
    expect(opened).toBeDefined();
    expect(opened.payload.sessionId).toBe(sessionId);
    expect(opened.payload.ready).toBe(true);
    expect(opened.payload.channelMode).toBe("CONTROL");
  });

  it("replies session.closed to a session.close", async () => {
    const { client, conn } = makeSubject();
    await client.start();
    await conn.simulateInbound(hostOpenedFrame(60));

    await conn.simulateInbound({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: "m-close",
      type: MessageType.SESSION_CLOSE,
      sentAt: new Date().toISOString(),
      sessionId,
      payload: {
        sessionId,
        reasonCode: "CONVERSATION_ARCHIVED",
        gracePeriodSeconds: 30,
      },
    });

    const closed = parseFirstByType(conn.sent, MessageType.SESSION_CLOSED);
    expect(closed).toBeDefined();
    expect(closed.payload.sessionId).toBe(sessionId);
    expect(closed.payload.reasonCode).toBe("CONVERSATION_ARCHIVED");
  });
});

describe("HostControlClient ack + dedupe discipline", () => {
  it("acks execution.event with highestContiguousSequence equal to envelope sequence", async () => {
    const { client, conn } = makeSubject();
    await client.start();
    await conn.simulateInbound(hostOpenedFrame(60));

    await conn.simulateInbound({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: "m-event",
      type: MessageType.EXECUTION_EVENT,
      sentAt: new Date().toISOString(),
      sessionId,
      executionId,
      sequence: 5,
      payload: {
        executionId,
        eventId: "55555555-5555-4555-8555-555555555555",
        eventType: "TOOL_STARTED",
        occurredAt: new Date().toISOString(),
        data: { toolName: "read_file" },
      },
    });

    const ack = parseFirstByType(conn.sent, MessageType.PROTOCOL_ACK);
    expect(ack).toBeDefined();
    expect(ack.payload.acknowledgedMessageId).toBe("m-event");
    expect(ack.payload.highestContiguousSequence).toBe(5);
    expect(ack.payload.status).toBe("DURABLY_RECORDED");
  });

  it("does NOT ack execution.delta", async () => {
    const { client, conn } = makeSubject();
    await client.start();
    await conn.simulateInbound(hostOpenedFrame(60));

    await conn.simulateInbound({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: "m-delta",
      type: MessageType.EXECUTION_DELTA,
      sentAt: new Date().toISOString(),
      executionId,
      sequence: 1,
      payload: {
        executionId,
        index: 0,
        content: "hi",
        contentType: "TEXT",
      },
    });

    const acks = conn.sent
      .map((raw) => JSON.parse(raw))
      .filter((f) => f.type === MessageType.PROTOCOL_ACK);
    expect(acks).toHaveLength(0);
  });

  it("dedupes by messageId: handler called once, duplicate durable frame re-acked", async () => {
    const { client, conn } = makeSubject();
    await client.start();
    await conn.simulateInbound(hostOpenedFrame(60));

    const frame = {
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: "m-event",
      type: MessageType.EXECUTION_EVENT,
      sentAt: new Date().toISOString(),
      sessionId,
      executionId,
      sequence: 7,
      payload: {
        executionId,
        eventId: "55555555-5555-4555-8555-555555555555",
        eventType: "TOOL_STARTED",
        occurredAt: new Date().toISOString(),
        data: { toolName: "read_file" },
      },
    };

    await conn.simulateInbound(frame);
    await conn.simulateInbound(frame);

    const acks = conn.sent
      .map((raw) => JSON.parse(raw))
      .filter((f) => f.type === MessageType.PROTOCOL_ACK);
    expect(acks).toHaveLength(2);
    expect(acks[0].payload.acknowledgedMessageId).toBe("m-event");
    expect(acks[0].payload.highestContiguousSequence).toBe(7);
    expect(acks[1].payload.acknowledgedMessageId).toBe("m-event");
    expect(acks[1].payload.highestContiguousSequence).toBe(7);
  });
});

describe("HostControlClient protocol.error handling", () => {
  it("closes the socket on UNSUPPORTED_VERSION protocol.error", async () => {
    const { client, conn } = makeSubject();
    await client.start();

    await conn.simulateInbound({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: "m-err",
      type: MessageType.PROTOCOL_ERROR,
      sentAt: new Date().toISOString(),
      payload: {
        code: "UNSUPPORTED_VERSION",
        message: "version 99 not supported",
        retryable: false,
        offendingMessageId: "m-open",
        scope: "CONNECTION",
      },
    });

    expect(conn.connected).toBe(false);
    expect(client.currentState).toBe("IDLE");
  });

  it("keeps the socket open on non-unsupported protocol.error", async () => {
    const { client, conn } = makeSubject();
    await client.start();
    await conn.simulateInbound(hostOpenedFrame(60));

    await conn.simulateInbound({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: "m-err",
      type: MessageType.PROTOCOL_ERROR,
      sentAt: new Date().toISOString(),
      payload: {
        code: "INVALID_STATE",
        message: "session not found",
        retryable: false,
        scope: "SESSION",
      },
    });

    expect(conn.connected).toBe(true);
    expect(client.currentState).toBe("OPEN");
  });
});

describe("HostControlClient execution.start handler", () => {
  it("delivers execution.start to the registered handler", async () => {
    const { client, conn } = makeSubject();
    const handler = vi.fn();
    client.onExecutionStart(handler);
    await client.start();
    await conn.simulateInbound(hostOpenedFrame(60));

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
        deadline: new Date().toISOString(),
      },
    });

    expect(handler).toHaveBeenCalledTimes(1);
    const arg = handler.mock.calls[0][0];
    expect(arg.type).toBe(MessageType.EXECUTION_START);
    expect(arg.payload.executionId).toBe(executionId);
  });
});

describe("HostControlClient sender API", () => {
  it("sends execution.delta, event, complete and failed with correct types", async () => {
    const { client, conn } = makeSubject();
    await client.start();
    await conn.simulateInbound(hostOpenedFrame(60));

    await client.sendExecutionDelta({
      executionId,
      index: 0,
      content: "delta",
      contentType: "TEXT",
    });
    await client.sendExecutionEvent({
      executionId,
      eventId: "55555555-5555-4555-8555-555555555555",
      eventType: "MODEL_STARTED",
      occurredAt: new Date().toISOString(),
      data: {},
    });
    await client.sendExecutionComplete({
      executionId,
      completedAt: new Date().toISOString(),
    });
    await client.sendExecutionFailed({
      executionId,
      failedAt: new Date().toISOString(),
      error: { code: "PROVIDER_ERROR", message: "fail", retryable: false },
    });

    const types = conn.sent.map((raw) => JSON.parse(raw).type);
    expect(types).toContain(MessageType.EXECUTION_DELTA);
    expect(types).toContain(MessageType.EXECUTION_EVENT);
    expect(types).toContain(MessageType.EXECUTION_COMPLETE);
    expect(types).toContain(MessageType.EXECUTION_FAILED);
  });
});

// ============================================================
// §7.5 (A1) dedicated session channel
// ============================================================

const channelEndpoint =
  "wss://engine.example/api/v1/agent/host/ws/channel";

describe("HostControlClient §7.5 channel handshake", () => {
  it("opens the channel socket and awaits channel.opened on a channel-bearing session.open", async () => {
    const { client, conn, tokens, lifecycle, channelConns } = makeSubject();
    await client.start();
    await conn.simulateInbound(hostOpenedFrame(60));

    // Kick off session.open (it awaits channel.opened internally) and let
    // the channel.open frame be sent BEFORE the reply arrives.
    const openPromise = simulateSessionOpen(conn, sessionOpenPayload({
      channel: { endpoint: channelEndpoint, token: "myr_chn_offer_token" },
    }));
    // The channel.opened reply must be delivered for the open to complete.
    await vi.advanceTimersByTimeAsync(1);
    expect(channelConns).toHaveLength(1);
    await channelConns[0].simulateInbound({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: "m-chn-opened",
      type: MessageType.CHANNEL_OPENED,
      sentAt: new Date().toISOString(),
      sessionId,
      payload: { sessionId, highestContiguousSequence: 0 },
    });
    await openPromise;

    // The channel socket was dialed with the same HOST_JWT auth.
    expect(channelConns).toHaveLength(1);
    expect(channelConns[0].connectToken).toBe("host-token");
    expect(tokens.getAccessToken).toHaveBeenCalled();

    // channel.open was the FIRST frame on the channel socket.
    const sent = channelConns[0].sent.map((raw) => JSON.parse(raw));
    expect(sent).toHaveLength(1);
    expect(sent[0].type).toBe(MessageType.CHANNEL_OPEN);
    expect(sent[0].payload.sessionId).toBe(sessionId);
    expect(sent[0].payload.resumeFromSequence).toBe(0);
    // The single-use token rides the payload, NOT the auth.
    expect(sent[0].payload.token).toBe("myr_chn_offer_token");

    // session.opened confirms the channel mode.
    const opened = parseFirstByType(conn.sent, MessageType.SESSION_OPENED);
    expect(opened).toBeDefined();
    expect(opened.payload.channelMode).toBe("CHANNEL");

    // The lifecycle recorded the bound channel.
    expect(lifecycle.channels.get(sessionId)?.alive).toBe(true);
  });

  it("carries the registry's durable cursor as resumeFromSequence", async () => {
    const { client, conn, lifecycle, channelConns } = makeSubject();
    await client.start();
    await conn.simulateInbound(hostOpenedFrame(60));

    // A durable frame on the control socket advances the registry cursor.
    await conn.simulateInbound({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: "m-event",
      type: MessageType.EXECUTION_EVENT,
      sentAt: new Date().toISOString(),
      sessionId,
      executionId,
      sequence: 9,
      payload: {
        executionId,
        eventId: "55555555-5555-4555-8555-555555555555",
        eventType: "TOOL_STARTED",
        occurredAt: new Date().toISOString(),
        data: {},
      },
    });
    expect(lifecycle.cursor).toBe(9);

    const openPromise = simulateSessionOpen(conn, sessionOpenPayload({
      channel: { endpoint: channelEndpoint, token: "tok" },
    }));
    await vi.advanceTimersByTimeAsync(1);
    await channelConns[0].simulateInbound({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: "m-chn-opened",
      type: MessageType.CHANNEL_OPENED,
      sentAt: new Date().toISOString(),
      sessionId,
      payload: { sessionId, highestContiguousSequence: 9 },
    });
    await openPromise;

    const sent = channelConns[0].sent.map((raw) => JSON.parse(raw));
    expect(sent[0].payload.resumeFromSequence).toBe(9);
  });

  it("does NOT open a channel socket when session.open carries channel null", async () => {
    const { client, conn, channelConns } = makeSubject();
    await client.start();
    await conn.simulateInbound(hostOpenedFrame(60));

    await simulateSessionOpen(conn, sessionOpenPayload({ channel: null }));

    expect(channelConns).toHaveLength(0);
    const opened = parseFirstByType(conn.sent, MessageType.SESSION_OPENED);
    expect(opened).toBeDefined();
    expect(opened.payload.channelMode).toBe("CONTROL");
  });

  it("does NOT open a channel socket when session.open has no channel field", async () => {
    const { client, conn, channelConns } = makeSubject();
    await client.start();
    await conn.simulateInbound(hostOpenedFrame(60));

    await simulateSessionOpen(conn, sessionOpenPayload({}));

    expect(channelConns).toHaveLength(0);
    const opened = parseFirstByType(conn.sent, MessageType.SESSION_OPENED);
    expect(opened.payload.channelMode).toBe("CONTROL");
  });

  it("falls back to CONTROL when the channel handshake fails (non-fatal)", async () => {
    const { client, conn, channelConns } = makeSubject();
    await client.start();
    await conn.simulateInbound(hostOpenedFrame(60));

    // Kick off session.open (it awaits the handshake internally) and let
    // the channel.open frame be sent BEFORE the rejection arrives.
    const openPromise = simulateSessionOpen(conn, sessionOpenPayload({
      channel: { endpoint: channelEndpoint, token: "expired-token" },
    }));
    await vi.advanceTimersByTimeAsync(1);
    expect(channelConns).toHaveLength(1);
    // The engine rejects the bind with a protocol.error (expired token).
    await channelConns[0].simulateInbound({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: "m-err",
      type: MessageType.PROTOCOL_ERROR,
      sentAt: new Date().toISOString(),
      payload: {
        code: "INVALID_STATE",
        message: "channel.open rejected: invalid or misbound token",
        retryable: false,
        scope: "CONNECTION",
      },
    });
    await openPromise;

    const opened = parseFirstByType(conn.sent, MessageType.SESSION_OPENED);
    expect(opened).toBeDefined();
    expect(opened.payload.channelMode).toBe("CONTROL");
  });
});

describe("HostControlClient §7.5 dual-socket dispatch", () => {
  async function openBoundChannel(subject: {
    client: HostControlClient;
    conn: FakeHostControlConnection;
    channelConns: FakeChannelConnection[];
  }): Promise<FakeChannelConnection> {
    const { client, conn, channelConns } = subject;
    await client.start();
    await conn.simulateInbound(hostOpenedFrame(60));
    const openPromise = simulateSessionOpen(conn, sessionOpenPayload({
      channel: { endpoint: channelEndpoint, token: "tok" },
    }));
    await vi.advanceTimersByTimeAsync(1);
    const channel = channelConns[0];
    await channel.simulateInbound({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: "m-chn-opened",
      type: MessageType.CHANNEL_OPENED,
      sentAt: new Date().toISOString(),
      sessionId,
      payload: { sessionId, highestContiguousSequence: 0 },
    });
    await openPromise;
    return channel;
  }

  it("dispatches inbound execution.delta on the channel like a control-socket frame", async () => {
    const subject = makeSubject();
    const channel = await openBoundChannel(subject);
    const handler = vi.fn();
    subject.client.onExecutionStart(handler);

    const deltaId = "m-delta-channel";
    await channel.simulateInbound({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: deltaId,
      type: MessageType.EXECUTION_DELTA,
      sentAt: new Date().toISOString(),
      sessionId,
      executionId,
      sequence: 1,
      payload: { executionId, index: 0, content: "via-channel", contentType: "TEXT" },
    });

    // The shared dedupe saw it — a REPLAY of the same frame is dropped.
    await channel.simulateInbound({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: deltaId,
      type: MessageType.EXECUTION_DELTA,
      sentAt: new Date().toISOString(),
      sessionId,
      executionId,
      sequence: 1,
      payload: { executionId, index: 0, content: "via-channel", contentType: "TEXT" },
    });
    // No duplicate handling is observable for delta (no ack); assert via a
    // durable frame instead: the event acks exactly once even though it
    // arrived on the channel.
    await channel.simulateInbound({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: "m-event-channel",
      type: MessageType.EXECUTION_EVENT,
      sentAt: new Date().toISOString(),
      sessionId,
      executionId,
      sequence: 5,
      payload: {
        executionId,
        eventId: "55555555-5555-4555-8555-555555555555",
        eventType: "TOOL_STARTED",
        occurredAt: new Date().toISOString(),
        data: { toolName: "read_file" },
      },
    });

    const acks = subject.conn.sent
      .map((raw) => JSON.parse(raw))
      .filter((f) => f.type === MessageType.PROTOCOL_ACK);
    expect(acks).toHaveLength(1);
    expect(acks[0].payload.acknowledgedMessageId).toBe("m-event-channel");
    expect(acks[0].payload.highestContiguousSequence).toBe(5);
  });

  it("routes outbound execution frames to the bound channel when one is alive", async () => {
    const subject = makeSubject();
    const { client, conn } = subject;
    const channel = await openBoundChannel(subject);
    // Drive an execution.start so the client can map executionId → session.
    await conn.simulateInbound({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: "m-start",
      type: MessageType.EXECUTION_START,
      sentAt: new Date().toISOString(),
      sessionId,
      executionId,
      payload: { executionId, sessionId, sequenceNo: 1, deadline: new Date().toISOString() },
    });
    channel.sent.length = 0;
    conn.sent.length = 0;

    await client.sendExecutionDelta({
      executionId,
      index: 0,
      content: "prefer-channel",
      contentType: "TEXT",
    });
    await client.sendExecutionComplete({
      executionId,
      completedAt: new Date().toISOString(),
    });

    // Frames went to the CHANNEL, not the control socket.
    const channelTypes = channel.sent.map((raw) => JSON.parse(raw).type);
    expect(channelTypes).toContain(MessageType.EXECUTION_DELTA);
    expect(channelTypes).toContain(MessageType.EXECUTION_COMPLETE);
    expect(conn.sent).toHaveLength(0);
  });

  it("keeps session alive and control frames flowing after channel loss (non-fatal)", async () => {
    const subject = makeSubject();
    const { client, conn, lifecycle } = subject;
    const channel = await openBoundChannel(subject);
    await conn.simulateInbound({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: "m-start",
      type: MessageType.EXECUTION_START,
      sentAt: new Date().toISOString(),
      sessionId,
      executionId,
      payload: { executionId, sessionId, sequenceNo: 1, deadline: new Date().toISOString() },
    });

    // The channel dies mid-session.
    await channel.simulateClose(1006, "abnormal");
    await vi.advanceTimersByTimeAsync(0);

    // The session is NOT closed: no session.closed emitted by the client,
    // the control socket is still connected, and the registry marks dead.
    expect(conn.connected).toBe(true);
    expect(lifecycle.channels.get(sessionId)?.alive).toBe(false);
    const closes = conn.sent
      .map((raw) => JSON.parse(raw))
      .filter((f) => f.type === MessageType.SESSION_CLOSED);
    expect(closes).toHaveLength(0);

    // Outbound execution frames FALL BACK to the control socket.
    await client.sendExecutionDelta({
      executionId,
      index: 0,
      content: "after-loss",
      contentType: "TEXT",
    });
    const types = conn.sent.map((raw) => JSON.parse(raw).type);
    expect(types).toContain(MessageType.EXECUTION_DELTA);

    // The session.close arm still works (the session lifecycle is intact).
    await conn.simulateInbound({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: "m-close",
      type: MessageType.SESSION_CLOSE,
      sentAt: new Date().toISOString(),
      sessionId,
      payload: { sessionId, reasonCode: "CONVERSATION_ARCHIVED", gracePeriodSeconds: 30 },
    });
    const closed = parseFirstByType(conn.sent, MessageType.SESSION_CLOSED);
    expect(closed).toBeDefined();
    expect(closed.payload.reasonCode).toBe("CONVERSATION_ARCHIVED");
  });

  it("closes the channel socket on session.close", async () => {
    const subject = makeSubject();
    const { conn, lifecycle } = subject;
    const channel = await openBoundChannel(subject);
    expect(channel.connected).toBe(true);

    await conn.simulateInbound({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: "m-close",
      type: MessageType.SESSION_CLOSE,
      sentAt: new Date().toISOString(),
      sessionId,
      payload: { sessionId, reasonCode: "CONVERSATION_ARCHIVED", gracePeriodSeconds: 30 },
    });

    expect(channel.connected).toBe(false);
    expect(channel.closedWithReason).toBe("Session closed");
    expect(lifecycle.channels.has(sessionId)).toBe(false);
  });

  it("closes every channel socket on client.stop()", async () => {
    const subject = makeSubject();
    const { client } = subject;
    const channel = await openBoundChannel(subject);

    await client.stop("Host shutdown");
    expect(channel.connected).toBe(false);
  });
});
