// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { describe, it, expect, vi } from "vitest";
import { AgentSupervisor } from "./AgentSupervisor.js";
import type { ConversationReconnectConfig } from "./AgentSupervisor.js";
import type { ConversationConnectionLike } from "./ConversationSocket.js";
import { CloseCode, MessageType } from "../protocol/messages.js";
import { makeEnvelope, type Envelope, type RawEnvelope } from "../protocol/envelope.js";
import type {
  AuthContext,
  Logger,
  Task,
  WorkspaceHandle,
} from "../models/index.js";

/** A fake conversation connection that records sends and lets the test drive
 * inbound frames + disconnects. */
class FakeConversationConnection implements ConversationConnectionLike {
  connected = false;
  connectToken?: string;
  failOpen = false;
  readonly sent: Envelope[] = [];
  disconnectedWith?: string;
  onMessage?: (frame: RawEnvelope) => void | Promise<void>;
  onDisconnect?: (code: number, reason: string) => void | Promise<void>;

  async connect(token: string): Promise<void> {
    if (this.failOpen) {
      throw new Error("dial failed");
    }
    this.connectToken = token;
    this.connected = true;
  }

  async send(frame: Envelope): Promise<void> {
    this.sent.push(frame);
  }

  async disconnect(reason?: string): Promise<void> {
    this.connected = false;
    this.disconnectedWith = reason;
  }

  get isConnected(): boolean {
    return this.connected;
  }

  simulateInbound(frame: RawEnvelope): void | Promise<void> {
    return this.onMessage?.(frame);
  }

  simulateClose(code: number, reason: string): void | Promise<void> {
    return this.onDisconnect?.(code, reason);
  }
}

/** A concrete Supervisor that stubs the abstract seams and captures both the
 * control-socket sends and the worker-forwarded frames so the conversation
 * socket routing can be asserted in isolation. */
class TestSupervisor extends AgentSupervisor {
  readonly forwardedToWorker: RawEnvelope[] = [];
  readonly controlSent: Envelope[] = [];
  readonly conns: FakeConversationConnection[] = [];
  /** Backoff delays the reconnect loop asked for, in order. */
  readonly backoffs: number[] = [];
  token = "tok-1";
  nextConnFails = false;

  constructor(logger?: Logger, reconnect?: ConversationReconnectConfig) {
    super({
      engineUrl: "http://engine.local",
      role: "HEADLESS",
      ...(logger ? { logger } : {}),
      ...(reconnect ? { conversationReconnect: reconnect } : {}),
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

  protected createConversationConnection(opts: {
    homeNodeAddr: string;
    onMessage: (frame: RawEnvelope) => void | Promise<void>;
    onDisconnect: (code: number, reason: string) => void | Promise<void>;
  }): ConversationConnectionLike {
    const conn = new FakeConversationConnection();
    conn.failOpen = this.nextConnFails;
    conn.onMessage = opts.onMessage;
    conn.onDisconnect = opts.onDisconnect;
    this.conns.push(conn);
    return conn;
  }

  // Public test hooks onto the protected dispatch surface.
  bind(frame: RawEnvelope): Promise<void> {
    return this.onAgentBind(frame);
  }
  release(frame: RawEnvelope): Promise<void> {
    return this.onAgentRelease(frame);
  }
  route(frame: Envelope): Promise<void> {
    return this.routeWorkerFrame(frame);
  }
  closeSocket(
    conversationId: string,
    code: number,
    reason: string,
  ): void | Promise<void> {
    return this.onConversationSocketClosed(conversationId, code, reason);
  }

  /** Record requested backoffs and resolve immediately — no real timers. */
  protected override delay(ms: number): Promise<void> {
    this.backoffs.push(ms);
    return Promise.resolve();
  }
}

function bindFrame(detail: {
  conversationId: string;
  homeNodeAddr?: string;
}): RawEnvelope {
  return {
    type: MessageType.AGENT_BIND,
    timestamp: new Date().toISOString(),
    payload: {
      conversationId: detail.conversationId,
      profileVersionId: "pv1",
      homeNodeId: "node-a",
      ...(detail.homeNodeAddr !== undefined
        ? { homeNodeAddr: detail.homeNodeAddr }
        : {}),
    },
  };
}

const silentLogger: Logger = {
  debug: vi.fn(),
  info: vi.fn(),
  warn: vi.fn(),
  error: vi.fn(),
};

describe("AgentSupervisor conversation socket", () => {
  it("opens a conversation socket and sends conversation.attach on agent.bind", async () => {
    const sup = new TestSupervisor(silentLogger);

    await sup.bind(bindFrame({ conversationId: "c1", homeNodeAddr: "10.0.0.5:8080" }));

    expect(sup.conns).toHaveLength(1);
    const conn = sup.conns[0];
    expect(conn.connected).toBe(true);
    expect(conn.connectToken).toBe("tok-1");
    expect(conn.sent).toHaveLength(1);
    expect(conn.sent[0].type).toBe(MessageType.CONVERSATION_ATTACH);
    expect(conn.sent[0].payload).toEqual({ agentId: "agent-1", conversationId: "c1" });
  });

  it("also hands the bind frame to the worker (byte-identical recording)", async () => {
    const sup = new TestSupervisor(silentLogger);

    await sup.bind(bindFrame({ conversationId: "c1", homeNodeAddr: "addr" }));

    expect(sup.forwardedToWorker).toHaveLength(1);
    expect(sup.forwardedToWorker[0].type).toBe(MessageType.AGENT_BIND);
  });

  it("routes message.delta over the matching conversation socket", async () => {
    const sup = new TestSupervisor(silentLogger);
    await sup.bind(bindFrame({ conversationId: "c1", homeNodeAddr: "addr" }));
    sup.controlSent.length = 0; // drop the bind ack so we assert only routing

    await sup.route(
      makeEnvelope(MessageType.MESSAGE_DELTA, {
        conversationId: "c1",
        sequenceNo: 1,
        deltaIndex: 0,
        content: "hi",
      }),
    );

    const conn = sup.conns[0];
    expect(conn.sent).toHaveLength(2); // attach + delta
    expect(conn.sent[1].type).toBe(MessageType.MESSAGE_DELTA);
    expect(sup.controlSent).toHaveLength(0);
  });

  it("routes task/workflow frames over the control socket", async () => {
    const sup = new TestSupervisor(silentLogger);
    await sup.bind(bindFrame({ conversationId: "c1", homeNodeAddr: "addr" }));
    sup.controlSent.length = 0; // drop the bind ack so we assert only routing

    await sup.route(
      makeEnvelope(MessageType.TASK_PROGRESS, { taskId: "t1", percent: 50 }),
    );

    expect(sup.controlSent).toHaveLength(1);
    expect(sup.controlSent[0].type).toBe(MessageType.TASK_PROGRESS);
    expect(sup.conns[0].sent).toHaveLength(1); // attach only
  });

  it("falls back to the control socket when no conversation socket matches", async () => {
    const warn = vi.fn();
    const sup = new TestSupervisor({ debug: vi.fn(), info: vi.fn(), warn, error: vi.fn() });

    await sup.route(
      makeEnvelope(MessageType.MESSAGE_DELTA, {
        conversationId: "unknown",
        sequenceNo: 1,
        deltaIndex: 0,
        content: "hi",
      }),
    );

    expect(sup.controlSent).toHaveLength(1);
    expect(sup.controlSent[0].type).toBe(MessageType.MESSAGE_DELTA);
    expect(warn).toHaveBeenCalled();
  });

  it("forwards conversation-socket inbound frames to the worker", async () => {
    const sup = new TestSupervisor(silentLogger);
    await sup.bind(bindFrame({ conversationId: "c1", homeNodeAddr: "addr" }));

    await sup.conns[0].simulateInbound({
      type: MessageType.CONVERSATION_TURN_ASSIGN,
      timestamp: new Date().toISOString(),
      payload: { conversationId: "c1" },
    });

    // bind + the inbound turn assign
    expect(sup.forwardedToWorker.map((f) => f.type)).toContain(
      MessageType.CONVERSATION_TURN_ASSIGN,
    );
  });

  it("closes the conversation socket on agent.release", async () => {
    const sup = new TestSupervisor(silentLogger);
    await sup.bind(bindFrame({ conversationId: "c1", homeNodeAddr: "addr" }));
    sup.controlSent.length = 0; // drop the bind ack so we assert only routing

    await sup.release({
      type: MessageType.AGENT_RELEASE,
      timestamp: new Date().toISOString(),
      payload: { conversationId: "c1", reason: "turn complete" },
    });

    expect(sup.conns[0].connected).toBe(false);
    expect(sup.forwardedToWorker.map((f) => f.type)).toContain(
      MessageType.AGENT_RELEASE,
    );

    // After release, conversation-keyed frames fall back to control.
    await sup.route(
      makeEnvelope(MessageType.MESSAGE_DELTA, {
        conversationId: "c1",
        sequenceNo: 1,
        deltaIndex: 0,
        content: "late",
      }),
    );
    expect(sup.controlSent).toHaveLength(1);
  });

  it("falls back to the control engine URL when homeNodeAddr is absent", async () => {
    const sup = new TestSupervisor(silentLogger);

    await sup.bind(bindFrame({ conversationId: "c1" }));

    expect(sup.conns).toHaveLength(1);
    expect(sup.conns[0].connected).toBe(true);
  });

  it("drops the socket if dialing the home node fails", async () => {
    const sup = new TestSupervisor(silentLogger);
    sup.nextConnFails = true;

    await sup.bind(bindFrame({ conversationId: "c1", homeNodeAddr: "addr" }));
    sup.controlSent.length = 0; // drop the bind nack so we assert only routing

    // Socket failed to open and was forgotten — a later frame falls back.
    await sup.route(
      makeEnvelope(MessageType.MESSAGE_DELTA, {
        conversationId: "c1",
        sequenceNo: 1,
        deltaIndex: 0,
        content: "hi",
      }),
    );
    expect(sup.controlSent).toHaveLength(1);
  });

  it("reaps the socket on a clean transport close", async () => {
    const sup = new TestSupervisor(silentLogger);
    await sup.bind(bindFrame({ conversationId: "c1", homeNodeAddr: "addr" }));
    sup.controlSent.length = 0; // drop the bind ack so we assert only routing

    // A clean (NORMAL) close is an engine-side drain — forget the socket, do
    // not re-home (§9.11).
    await sup.closeSocket("c1", CloseCode.NORMAL, "drain");

    await sup.route(
      makeEnvelope(MessageType.MESSAGE_DELTA, {
        conversationId: "c1",
        sequenceNo: 1,
        deltaIndex: 0,
        content: "hi",
      }),
    );
    expect(sup.controlSent).toHaveLength(1);
    expect(sup.backoffs).toHaveLength(0); // no reconnect attempted
  });

  it("emits agent.bind.ack on the control socket once the conversation socket opens", async () => {
    const sup = new TestSupervisor(silentLogger);

    await sup.bind(bindFrame({ conversationId: "c1", homeNodeAddr: "addr" }));

    const acks = sup.controlSent.filter(
      (f) => f.type === MessageType.AGENT_BIND_ACK,
    );
    expect(acks).toHaveLength(1);
    expect(acks[0].payload).toEqual({ conversationId: "c1" });
    expect(
      sup.controlSent.some((f) => f.type === MessageType.AGENT_BIND_NACK),
    ).toBe(false);
  });

  it("emits agent.bind.nack on the control socket when dialing fails", async () => {
    const sup = new TestSupervisor(silentLogger);
    sup.nextConnFails = true;

    await sup.bind(bindFrame({ conversationId: "c1", homeNodeAddr: "addr" }));

    const nacks = sup.controlSent.filter(
      (f) => f.type === MessageType.AGENT_BIND_NACK,
    );
    expect(nacks).toHaveLength(1);
    expect(nacks[0].payload).toMatchObject({ conversationId: "c1" });
    expect(
      sup.controlSent.some((f) => f.type === MessageType.AGENT_BIND_ACK),
    ).toBe(false);
  });

  // ---- §9.11 conversation-socket reconnect (home node UP) ----------------

  it("reconnects to the same home node and re-sends conversation.attach on an abnormal drop", async () => {
    const sup = new TestSupervisor(silentLogger);
    await sup.bind(bindFrame({ conversationId: "c1", homeNodeAddr: "10.0.0.5:8080" }));
    expect(sup.conns).toHaveLength(1);

    // Home node still reachable but the socket blips — re-home to the same node.
    await sup.closeSocket("c1", CloseCode.GOING_AWAY, "blip");

    // A fresh connection was dialed and conversation.attach re-sent.
    expect(sup.conns).toHaveLength(2);
    expect(sup.conns[1].connected).toBe(true);
    expect(sup.conns[1].sent[0].type).toBe(MessageType.CONVERSATION_ATTACH);
    expect(sup.conns[1].sent[0].payload).toEqual({
      agentId: "agent-1",
      conversationId: "c1",
    });
    // One backoff before the (successful) first retry.
    expect(sup.backoffs).toEqual([500]);

    // The reconnected socket carries conversation-keyed frames again.
    sup.controlSent.length = 0;
    await sup.route(
      makeEnvelope(MessageType.MESSAGE_DELTA, {
        conversationId: "c1",
        sequenceNo: 1,
        deltaIndex: 0,
        content: "hi",
      }),
    );
    expect(
      sup.conns[1].sent.some((f) => f.type === MessageType.MESSAGE_DELTA),
    ).toBe(true);
    expect(sup.controlSent).toHaveLength(0);
  });

  it("uses exponential backoff and stops dialing after the attempt cap", async () => {
    const sup = new TestSupervisor(silentLogger, {
      maxAttempts: 3,
      baseBackoffMs: 500,
    });
    await sup.bind(bindFrame({ conversationId: "c1", homeNodeAddr: "10.0.0.5:8080" }));
    expect(sup.conns).toHaveLength(1);

    // Home node is gone: every reconnect dial fails.
    sup.nextConnFails = true;
    await sup.closeSocket("c1", CloseCode.GOING_AWAY, "node down");

    // 3 reconnect dials (conns 2..4), each preceded by an exponential backoff.
    expect(sup.conns).toHaveLength(1 + 3);
    expect(sup.backoffs).toEqual([500, 1000, 2000]);
    // Σ(backoff)=3500ms — well under the engine 70s host-lost-threshold.
    expect(sup.backoffs.reduce((a, b) => a + b, 0)).toBeLessThan(70_000);

    // Exhausted → stops dialing: a further drop is a no-op (home-addr cleared).
    const dialedBefore = sup.conns.length;
    await sup.closeSocket("c1", CloseCode.GOING_AWAY, "again");
    expect(sup.conns).toHaveLength(dialedBefore);
  });
});
