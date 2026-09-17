// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * §13 (A2) reconnect & reconciliation — SDK-side tests.
 *
 * Covers: drop retention, host.resume on reconnect, KEEP re-bind + replay
 * absorption, CANCEL_EXECUTION semantics, CLOSE teardown, protocol.error
 * fallback to fresh host.open, and retention expiry.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import {
  HostControlClient,
  type ChannelConnection,
  type HostControlConnection,
  type HostRetentionLifecycle,
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
const sessionId2 = "34343434-3434-4343-8434-343434343434";
const executionId = "44444444-4444-4444-8444-444444444444";
const hostInstanceId = "22222222-2222-4222-8222-222222222222";
const eventUuid = "55555555-5555-4555-8555-555555555555";

/** Fake transport that records raw outbound unified frames and lets tests
 * drive inbound frames + close events (same shape as the A1 test fake). */
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

/** Fake channel socket (mirrors the A1 fake; unused by A2 assertions except
 * where channel re-bind behavior matters). */
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

/** Retained session as the fake registry tracks it. */
interface FakeSessionState {
  sessionId: string;
  cursor: number;
  vault: Map<string, string>;
  channel: { alive: boolean } | null;
  disconnected: boolean;
  modelClosed: boolean;
}

/** Fake §13 retention lifecycle (mirrors SessionRegistry + cancel hook). */
class FakeRetentionLifecycle implements HostRetentionLifecycle {
  readonly sessions = new Map<string, FakeSessionState>();
  readonly cancelledExecutions: string[] = [];
  readonly closedSessions: string[] = [];
  readonly reboundSessions: string[] = [];

  openSession(sessionId: string, cursor = 0): void {
    this.sessions.set(sessionId, {
      sessionId,
      cursor,
      vault: new Map([["model-key", "plaintext"]]),
      channel: null,
      disconnected: false,
      modelClosed: false,
    });
  }

  getHighestContiguousSequence(sid: string): number {
    return this.sessions.get(sid)?.cursor ?? 0;
  }

  observeDurableSequence(sid: string, sequence: number): void {
    const s = this.sessions.get(sid);
    if (s && sequence > s.cursor) {
      s.cursor = sequence;
    }
  }

  bindChannel(sid: string, _socket: unknown, opened: { highestContiguousSequence: number }): void {
    const s = this.sessions.get(sid);
    if (s) {
      s.channel = { alive: true };
      s.cursor = Math.max(s.cursor, opened.highestContiguousSequence);
    }
  }

  markChannelDead(sid: string): void {
    const ch = this.sessions.get(sid)?.channel;
    if (ch) {
      ch.alive = false;
    }
  }

  unbindChannel(sid: string): { socket: unknown } | null {
    const s = this.sessions.get(sid);
    const channel = s?.channel;
    if (s) {
      s.channel = null;
    }
    return channel ? { socket: null } : null;
  }

  markAllDisconnected(): void {
    for (const s of this.sessions.values()) {
      s.disconnected = true;
      if (s.channel) {
        s.channel.alive = false;
      }
    }
  }

  retainedSessionIds(): string[] {
    return [...this.sessions.entries()].filter(([, s]) => s.disconnected).map(([id]) => id);
  }

  buildRetainedSummaries(): Array<{ sessionId: string; state: string; capacityHeld: boolean }> {
    return this.retainedSessionIds().map((sid) => ({
      sessionId: sid,
      state: "ACTIVE",
      capacityHeld: true,
    }));
  }

  rebindAfterReconcile(sid: string): number {
    const s = this.sessions.get(sid);
    if (!s) {
      return 0;
    }
    s.disconnected = false;
    s.channel = null;
    this.reboundSessions.push(sid);
    return s.cursor;
  }

  closeRetained(sid: string): void {
    const s = this.sessions.get(sid);
    if (!s) {
      return;
    }
    s.vault.clear();
    s.modelClosed = true;
    this.sessions.delete(sid);
    this.closedSessions.push(sid);
  }

  /** Mirrors the registry helper: whether an entry is still retained. */
  isRetained(sid: string): boolean {
    return this.sessions.get(sid)?.disconnected ?? false;
  }

  cancelExecution(executionId: string): void {
    this.cancelledExecutions.push(executionId);
  }
}

interface TestSubject {
  client: HostControlClient;
  conn: FakeHostControlConnection;
  tokens: HostTokenProvider;
  retention: FakeRetentionLifecycle;
  channelConns: FakeChannelConnection[];
}

function makeSubject(options: { retentionWindowMs?: number } = {}): TestSubject {
  const conn = new FakeHostControlConnection();
  const tokens = makeTokenProvider();
  const retention = new FakeRetentionLifecycle();
  const channelConns: FakeChannelConnection[] = [];
  const client = new HostControlClient({
    engineUrl: "http://engine.local",
    tokenProvider: tokens,
    logger: silentLogger,
    connection: conn,
    poolSize: 4,
    runtimeVersion: "1.8.0",
    // The fake retention lifecycle doubles as the §7.5 session-lifecycle
    // collaborator (same surface the SessionRegistry implements).
    sessionLifecycle: retention,
    retention,
    ...(options.retentionWindowMs !== undefined
      ? { retentionWindowMs: options.retentionWindowMs }
      : {}),
    channelConnectionFactory: () => {
      const c = new FakeChannelConnection();
      channelConns.push(c);
      return c;
    },
  });
  return { client, conn, tokens, retention, channelConns };
}

function hostOpenedFrame(instanceId: string, intervalSeconds: number): Record<string, unknown> {
  // The messageId is unique per frame — across reconnects the dedupe LRU
  // would otherwise swallow a repeated host.opened.
  return {
    protocolVersion: SUPPORTED_PROTOCOL_VERSION,
    messageId: `m-opened-${instanceId}-${Math.random().toString(36).slice(2, 8)}`,
    type: MessageType.HOST_OPENED,
    sentAt: new Date().toISOString(),
    hostInstanceId: instanceId,
    payload: {
      hostInstanceId: instanceId,
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

function reconcileFrame(
  messageId: string,
  decisions: Array<Record<string, unknown>>,
  instance = hostInstanceId,
): Record<string, unknown> {
  return {
    protocolVersion: SUPPORTED_PROTOCOL_VERSION,
    messageId,
    type: MessageType.HOST_RECONCILE,
    sentAt: new Date().toISOString(),
    hostInstanceId: instance,
    payload: { hostInstanceId: instance, decisions },
  };
}

function parseByType(sent: string[], type: string): Array<Record<string, unknown>> {
  return sent
    .map((raw) => JSON.parse(raw) as Record<string, unknown>)
    .filter((f) => f.type === type);
}

/** Drive a session.open so the registry holds a session, then ack a durable
 * event so the registry cursor advances to `sequence`. */
async function openSessionWithCursor(
  subject: TestSubject,
  sid: string,
  cursor: number,
): Promise<void> {
  const { conn, retention } = subject;
  retention.openSession(sid);
  await conn.simulateInbound({
    protocolVersion: SUPPORTED_PROTOCOL_VERSION,
    messageId: `m-event-${sid}`,
    type: MessageType.EXECUTION_EVENT,
    sentAt: new Date().toISOString(),
    sessionId: sid,
    executionId,
    sequence: cursor,
    payload: {
      executionId,
      eventId: eventUuid,
      eventType: "TOOL_STARTED",
      occurredAt: new Date().toISOString(),
      data: { toolName: "read_file" },
    },
  });
}

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
});

afterEach(() => {
  vi.useRealTimers();
  vi.clearAllMocks();
});

describe("§13 drop retention (A2)", () => {
  it("retains sessions, cursors and ack ids across a drop — no teardown", async () => {
    const { client, conn, retention } = makeSubject();
    await client.start();
    await conn.simulateInbound(hostOpenedFrame(hostInstanceId, 60));
    await openSessionWithCursor({ client, conn, retention, channelConns: [] }, sessionId, 9);
    conn.sent.length = 0;

    // Drop the control socket.
    await conn.simulateClose(1006, "abnormal");
    await vi.advanceTimersByTimeAsync(1);

    // Sessions retained, cursor intact, no vault/model teardown.
    expect(retention.retainedSessionIds()).toEqual([sessionId]);
    expect(retention.sessions.get(sessionId)?.cursor).toBe(9);
    expect(retention.sessions.get(sessionId)?.vault.size).toBe(1);
    expect(retention.sessions.get(sessionId)?.modelClosed).toBe(false);
    expect(retention.closedSessions).toHaveLength(0);
    // The drop armed the retention window and did NOT tear anything down
    // (the state machine left OPEN toward RECOVERING/reconnect).
    expect(client.currentState).not.toBe("OPEN");
    expect(retention.retainedSessionIds()).toEqual([sessionId]);
  });

  it("tears down when retention expires without a reconnect", async () => {
    const { client, conn, retention } = makeSubject({ retentionWindowMs: 5_000 });
    await client.start();
    await conn.simulateInbound(hostOpenedFrame(hostInstanceId, 60));
    await openSessionWithCursor({ client, conn, retention, channelConns: [] }, sessionId, 4);

    await conn.simulateClose(1006, "abnormal");
    expect(retention.retainedSessionIds()).toEqual([sessionId]);

    // No reconnect within the window → teardown (the engine expired too).
    await vi.advanceTimersByTimeAsync(5_001);
    expect(retention.retainedSessionIds()).toHaveLength(0);
    expect(retention.closedSessions).toEqual([sessionId]);
  });

  it("stop() clears the retention timer without tearing down retained sessions", async () => {
    const { client, conn, retention } = makeSubject({ retentionWindowMs: 5_000 });
    await client.start();
    await conn.simulateInbound(hostOpenedFrame(hostInstanceId, 60));
    await openSessionWithCursor({ client, conn, retention, channelConns: [] }, sessionId, 2);
    await conn.simulateClose(1006, "abnormal");

    await client.stop("Host shutdown");
    await vi.advanceTimersByTimeAsync(6_000);

    // stop() is a deliberate host shutdown — the expiry never fired.
    expect(retention.retainedSessionIds()).toEqual([sessionId]);
    expect(retention.closedSessions).toHaveLength(0);
  });
});

describe("§13 host.resume on reconnect (A2)", () => {
  async function dropAndReconnect(
    subject: TestSubject,
    newInstance = hostInstanceId,
  ): Promise<void> {
    const { conn } = subject;
    await conn.simulateClose(1006, "abnormal");
    await vi.advanceTimersByTimeAsync(2_000);
    // The engine's fresh host.opened on the re-dialed socket. handleHostOpened
    // runs the resume handshake (host.resume → host.reconcile) internally, so
    // the dispatch must NOT be awaited — flush until host.resume is on the
    // wire, then the test delivers host.reconcile.
    void conn.simulateInbound(hostOpenedFrame(newInstance, 60));
    await vi.advanceTimersByTimeAsync(50);
  }

  it("sends host.resume with correct summaries on reconnect within the window", async () => {
    const { client, conn, retention } = makeSubject();
    await client.start();
    await conn.simulateInbound(hostOpenedFrame(hostInstanceId, 60));
    // The PREVIOUS instance's nonce — the §6.1 resume identity reported on
    // host.resume (the reconnect rotates the live one).
    const firstNonce = client.currentNonce;
    await openSessionWithCursor({ client, conn, retention, channelConns: [] }, sessionId, 9);
    await openSessionWithCursor(
      { client, conn, retention, channelConns: [] },
      sessionId2,
      12,
    );
    // Ack a durable terminal for sessionId — the resume report carries it.
    const terminalId = "m-terminal-1";
    await conn.simulateInbound({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: terminalId,
      type: MessageType.EXECUTION_COMPLETE,
      sentAt: new Date().toISOString(),
      sessionId,
      executionId,
      sequence: 10,
      payload: { executionId, completedAt: new Date().toISOString() },
    });
    conn.sent.length = 0;

    await dropAndReconnect({ client, conn, retention, channelConns: [] });

    const resumes = parseByType(conn.sent, MessageType.HOST_RESUME);
    expect(resumes).toHaveLength(1);
    const payload = resumes[0].payload as Record<string, unknown>;
    expect(payload.previousHostInstanceId).toBe(hostInstanceId);
    // The reported nonce is the PREVIOUS instance's nonce (the §6.1 resume
    // identity), not the freshly rotated one.
    expect(payload.instanceNonce).not.toBe(client.currentNonce);
    expect(payload.instanceNonce).toBe(firstNonce);
    const sessions = payload.sessions as Array<Record<string, unknown>>;
    expect(sessions).toHaveLength(2);
    const first = sessions.find((s) => s.sessionId === sessionId);
    expect(first?.state).toBe("ACTIVE");
    expect(first?.capacityHeld).toBe(true);
    expect(first?.lastSentSequence).toBe(10);
    expect(first?.lastAcknowledgedMessageId).toBe(terminalId);
    const second = sessions.find((s) => s.sessionId === sessionId2);
    expect(second?.lastSentSequence).toBe(12);
    // sessionId2's durable event was acked with its own messageId.
    expect(second?.lastAcknowledgedMessageId).toBe(`m-event-${sessionId2}`);
  });

  it("KEEP decision: session re-bound, replayed durable events dispatched, duplicate absorbed", async () => {
    const { client, conn, retention } = makeSubject();
    await client.start();
    await conn.simulateInbound(hostOpenedFrame(hostInstanceId, 60));
    await openSessionWithCursor({ client, conn, retention, channelConns: [] }, sessionId, 5);
    conn.sent.length = 0;

    await dropAndReconnect({ client, conn, retention, channelConns: [] });
    // The resume was answered with KEEP.
    await conn.simulateInbound(
      reconcileFrame("m-reconcile", [
        { sessionId, action: "KEEP", resumeFromSequence: 5 },
      ]),
    );
    await vi.advanceTimersByTimeAsync(1);

    // The session was re-bound (disconnected marker cleared).
    expect(retention.reboundSessions).toEqual([sessionId]);
    expect(retention.isRetained(sessionId)).toBe(false);

    // The engine replays its durable events after resumeFromSequence —
    // the resumed socket carries sequence 6..7, absorbed by cursor + ack.
    conn.sent.length = 0;
    await conn.simulateInbound({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: "m-replay-6",
      type: MessageType.EXECUTION_EVENT,
      sentAt: new Date().toISOString(),
      sessionId,
      executionId,
      sequence: 6,
      payload: {
        executionId,
        eventId: eventUuid,
        eventType: "MODEL_STARTED",
        occurredAt: new Date().toISOString(),
        data: {},
      },
    });
    await conn.simulateInbound({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: "m-replay-6",
      type: MessageType.EXECUTION_EVENT,
      sentAt: new Date().toISOString(),
      sessionId,
      executionId,
      sequence: 6,
      payload: {
        executionId,
        eventId: eventUuid,
        eventType: "MODEL_STARTED",
        occurredAt: new Date().toISOString(),
        data: {},
      },
    });

    // The replay advanced the cursor and was acked; the duplicate replay
    // was absorbed for HANDLING (no second dispatch) but re-acked per §12.1
    // (a seen durable frame re-acks so the engine's cursor stays authoritative).
    expect(retention.sessions.get(sessionId)?.cursor).toBe(6);
    const acks = parseByType(conn.sent, MessageType.PROTOCOL_ACK);
    expect(acks).toHaveLength(2);
    expect(acks[0].payload.acknowledgedMessageId).toBe("m-replay-6");
    expect(acks[0].payload.highestContiguousSequence).toBe(6);
    expect(acks[1].payload.acknowledgedMessageId).toBe("m-replay-6");
  });

  it("CANCEL_EXECUTION: cancel hook applied, no execution.cancel frame sent", async () => {
    const { client, conn, retention } = makeSubject();
    await client.start();
    await conn.simulateInbound(hostOpenedFrame(hostInstanceId, 60));
    await openSessionWithCursor({ client, conn, retention, channelConns: [] }, sessionId, 3);
    conn.sent.length = 0;

    await dropAndReconnect({ client, conn, retention, channelConns: [] });
    await conn.simulateInbound(
      reconcileFrame("m-reconcile", [
        {
          sessionId,
          action: "CANCEL_EXECUTION",
          executionId,
        },
      ]),
    );
    await vi.advanceTimersByTimeAsync(1);

    // The hook applied the cancellation (the runner aborts + emits the
    // normal execution.cancelled terminal — engine-side, not a new frame).
    expect(retention.cancelledExecutions).toEqual([executionId]);
    // The decision closed the one-shot session (registry + vault dropped).
    expect(retention.closedSessions).toEqual([sessionId]);
    // NO separate execution.cancel frame was sent after the decision.
    expect(parseByType(conn.sent, MessageType.EXECUTION_CANCEL)).toHaveLength(0);
  });

  it("CLOSE: registry + vault cleared for that session", async () => {
    const { client, conn, retention } = makeSubject();
    await client.start();
    await conn.simulateInbound(hostOpenedFrame(hostInstanceId, 60));
    await openSessionWithCursor({ client, conn, retention, channelConns: [] }, sessionId, 3);
    await openSessionWithCursor(
      { client, conn, retention, channelConns: [] },
      sessionId2,
      2,
    );
    conn.sent.length = 0;

    await dropAndReconnect({ client, conn, retention, channelConns: [] });
    await conn.simulateInbound(
      reconcileFrame("m-reconcile", [
        { sessionId, action: "CLOSE", reasonCode: "TASK_ALREADY_RETRIED" },
        { sessionId: sessionId2, action: "KEEP", resumeFromSequence: 2 },
      ]),
    );
    await vi.advanceTimersByTimeAsync(1);

    // The CLOSE decision dropped exactly its session.
    expect(retention.closedSessions).toEqual([sessionId]);
    expect(retention.sessions.has(sessionId)).toBe(false);
    // The KEEP decision kept the other one.
    expect(retention.sessions.has(sessionId2)).toBe(true);
    expect(retention.isRetained(sessionId2)).toBe(false);
  });

  it("protocol.error on resume wipes retained state and falls back to fresh host.open", async () => {
    const { client, conn, retention } = makeSubject();
    await client.start();
    await conn.simulateInbound(hostOpenedFrame(hostInstanceId, 60));
    await openSessionWithCursor({ client, conn, retention, channelConns: [] }, sessionId, 7);
    conn.sent.length = 0;

    // Drop → resume is sent on reconnect (the same fire-and-flush as the
    // other resume tests — handleHostOpened blocks awaiting host.reconcile).
    await conn.simulateClose(1006, "abnormal");
    await vi.advanceTimersByTimeAsync(2_000);
    void conn.simulateInbound(hostOpenedFrame(hostInstanceId, 60));
    await vi.advanceTimersByTimeAsync(50);
    expect(parseByType(conn.sent, MessageType.HOST_RESUME)).toHaveLength(1);
    expect(retention.retainedSessionIds()).toEqual([sessionId]);

    // The engine rejects: no matching RECOVERING instance.
    await conn.simulateInbound({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: "m-err",
      type: MessageType.PROTOCOL_ERROR,
      sentAt: new Date().toISOString(),
      payload: {
        code: "SESSION_NOT_FOUND",
        message: "host.resume cannot re-adopt an instance — use host.open",
        retryable: false,
        offendingMessageId: "m-resume",
        scope: "CONNECTION",
      },
    });
    await vi.advanceTimersByTimeAsync(1);

    // Retention wiped, window closed.
    expect(retention.retainedSessionIds()).toHaveLength(0);
    expect(retention.closedSessions).toEqual([sessionId]);
    expect(retention.isRetained(sessionId)).toBe(false);
    // The client is on the fresh-host path — connected, OPEN, no resume pending.
    expect(client.currentState).toBe("OPEN");
    expect(conn.connected).toBe(true);
  });
});