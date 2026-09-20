// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Unified host-control socket client (§6–§11).
 *
 * Maintains the single WebSocket to `/api/v1/agent/host/ws`, runs the host
 * lifecycle FSM (CONNECTING → host.open → host.opened → heartbeat), handles
 * session allocation arms, and forwards execution commands to a registered
 * handler. It is intentionally independent of the legacy `AgentSupervisor`
 * conversation-socket machinery: the unified protocol replaces the bind/attach
 * path with session.offer/open and execution.start frames.
 */
import { CloseCode } from "../protocol/messages.js";
import {
  encodeUnifiedFrame,
  MessageType as UnifiedMessageType,
  parseUnifiedFrame,
  ProtocolErrorCode,
  ReconcileAction,
  SUPPORTED_PROTOCOL_VERSION,
  type ChannelOpenedPayload,
  type ExecutionAcceptPayload,
  type ExecutionCancelPayload,
  type ExecutionCancelledPayload,
  type ExecutionCompletePayload,
  type ExecutionDeltaPayload,
  type ExecutionEventPayload,
  type ExecutionFailedPayload,
  type ExecutionApprovalRequestedPayload,
  type ExecutionPausedPayload,
  type ExecutionRejectPayload,
  type HostCapacityPayload,
  type HostHeartbeatPayload,
  type HostOpenPayload,
  type HostOpenedPayload,
  type HostResumePayload,
  type ParsedUnifiedFrame,
  type ProtocolAckPayload,
  type ProtocolErrorPayload,
  type ReconcileDecision,
  type SessionAcceptPayload,
  type SessionClosedPayload,
  type SessionOfferPayload,
  type SessionOpenPayload,
  type SessionChannelOffer,
  type SessionRejectPayload,
  type SessionOpenedPayload,
  type UnifiedFrame,
} from "../protocol/unifiedFrames.js";
import { randomUUID } from "node:crypto";
import { hostname } from "node:os";
import type { Logger } from "../models/index.js";
import { WebSocket, type RawData } from "ws";

/** Provider for the HOST_JWT access token used on the control socket. */
export interface HostTokenProvider {
  getAccessToken(): Promise<string>;
  refreshTokens(): Promise<string>;
  reRegister(): Promise<string>;
}

/** Raw WebSocket abstraction used by {@link HostControlClient}. */
export interface HostControlConnection {
  connect(token: string): Promise<void>;
  disconnect(reason?: string): Promise<void>;
  send(raw: string): Promise<void>;
  readonly isConnected: boolean;
  onMessage?: (raw: string) => void | Promise<void>;
  onConnect?: () => void | Promise<void>;
  onDisconnect?: (code: number, reason: string) => void | Promise<void>;
}

/** Options for {@link HostControlClient}. */
export interface HostControlClientOptions {
  /** Base Engine URL (http(s):// or ws(s)://). */
  engineUrl: string;
  /** Supplies HOST_JWT access/refresh tokens and re-registration. */
  tokenProvider: HostTokenProvider;
  /** Optional logger; defaults to `console`. */
  logger?: Logger;
  /** Path to the host-control socket. */
  path?: string;
  /** SDK runtime version reported in `host.open`. */
  runtimeVersion?: string;
  /** Worker-pool size reported in `host.open` and heartbeat. */
  poolSize?: number;
  /** Optional capabilities map reported in `host.open`. */
  capabilities?: Record<string, unknown>;
  /** Optional capacity map reported in `host.open` and heartbeat. */
  reportedCapacity?: Record<string, unknown>;
  /**
   * Local-owner model (§3.7/§4.1): the id of the user logged into the
   * plugin (LOCAL hosts) — stamped onto `host.open` so the engine pins
   * instance ownership. Absent (headless/MANAGED) → field omitted.
   */
  ownerUserId?: string;
  /** Maximum dedupe cache entries for inbound messageId values. */
  dedupeLimit?: number;
  /** Connection override (tests inject a fake). */
  connection?: HostControlConnection;
  /**
   * Invoked once per `host.opened` carrying a PSK (design
   * 2026-09-16-credential-envelope-delivery.md §6/§10). The client buffers
   * the bytes in process memory only; the callback hands them to the
   * SessionRegistry (`setPsk`). Never logged, never persisted.
   */
  onPsk?: (psk: Uint8Array) => void;
  /**
   * §7.5 (A1): session-lifecycle collaborator. When a `session.open` carries
   * a `channel` offer the client opens the dedicated channel socket, runs the
   * channel.open/opened handshake, and registers the bound socket here;
   * channel loss is NON-fatal (the session keeps riding the control socket).
   * Inbound execution frames on the channel socket are dispatched through the
   * same pipeline as control-socket frames. Optional — when omitted the
   * client stays control-only (existing behavior unchanged).
   */
  sessionLifecycle?: HostSessionLifecycle;
  /**
   * §7.5 (A1): channel socket factory override (tests inject a fake).
   * Default dials the real `WebSocketChannelConnection`.
   */
  channelConnectionFactory?: (endpoint: string) => ChannelConnection;
  /**
   * §13 (A2): session-lifecycle retention collaborators. Implemented by the
   * SessionRegistry. When wired, a control-socket drop RETAINS sessions for
   * `retentionWindowMs` and the reconnect path sends `host.resume` +
   * applies `host.reconcile` decisions instead of tearing everything down.
   * Optional — when omitted the client keeps the pre-A2 behavior.
   */
  retention?: HostRetentionLifecycle;
  /**
   * §13 (A2): how long retained state survives a drop, in ms. Must not
   * exceed the engine's `myrmec.host.recovery.retain-seconds` (default
   * 300s) — the engine expires its side first. Default 300_000.
   */
  retentionWindowMs?: number;
}

/**
 * §13 (A2): the retention collaborators the client needs across a
 * control-socket drop. Implemented by the SessionRegistry (plus the
 * execution-cancellation hook the worker/executor layer supplies).
 */
export interface HostRetentionLifecycle extends HostSessionLifecycle {
  /** Mark every session as retained (survive the drop). */
  markAllDisconnected(): void;
  /** All retained session ids, insertion order. */
  retainedSessionIds(): string[];
  /** §13 host.resume summaries for every retained session. */
  buildRetainedSummaries(): Array<{
    sessionId: string;
    state: string;
    capacityHeld: boolean;
  }>;
  /** Re-bind a session the engine decided to KEEP. */
  rebindAfterReconcile(sessionId: string): number;
  /** Drop a session per a CLOSE/unknown decision (vault included). */
  closeRetained(sessionId: string): void;
  /**
   * Apply cancellation semantics for one execution (§13: a
   * CANCEL_EXECUTION decision IS the cancellation command — the in-flight
   * turn is aborted and the normal execution.cancelled terminal frame is
   * emitted; no separate execution.cancel frame is sent).
   */
  cancelExecution(executionId: string): void;
}

/**
 * §7.5 (A1): one bound dedicated-channel client per session. Owns the
 * channel socket + the channel.open → channel.opened handshake, forwards
 * inbound frames through the shared dispatch, and reports death (close /
 * error) so the session falls back to the control socket — never closing
 * the session itself.
 */
export class SessionChannelClient {
  private readonly connection: ChannelConnection;
  private opened: ChannelOpenedPayload | null = null;

  constructor(
    readonly sessionId: string,
    private readonly offer: SessionChannelOffer,
    private readonly getAccessToken: () => Promise<string>,
    private readonly callbacks: {
      onFrame: (raw: string) => void | Promise<void>;
      onDead: (reason: string) => void;
    },
    private readonly log: Logger,
    connectionFactory?: (endpoint: string) => ChannelConnection,
  ) {
    this.connection = (connectionFactory ?? ((endpoint) => new WebSocketChannelConnection(endpoint)))(
      offer.endpoint,
    );
    this.connection.onMessage = (raw) => this.handleMessage(raw);
    this.connection.onClose = (code, reason) => {
      if (this.opened) {
        // Only report death after a successful bind; a pre-bind failure is
        // handled by open() itself.
        this.callbacks.onDead(`code=${code} reason=${reason}`);
      }
    };
  }

  /** True while the channel socket is open (post-bind). */
  get isOpen(): boolean {
    return this.opened !== null && this.connection.isOpen;
  }

  /**
   * Run the §7.5 handshake: connect (same HOST_JWT gate as the control
   * socket), send channel.open with the session's durable cursor + the
   * single-use offer token, await channel.opened. Never throws.
   *
   * @returns the channel.opened payload, or null on any failure.
   */
  async open(resumeFromSequence: number): Promise<ChannelOpenedPayload | null> {
    try {
      const token = await this.getAccessToken();
      await this.connection.connect(token);
      await this.connection.send(
        JSON.stringify({
          protocolVersion: SUPPORTED_PROTOCOL_VERSION,
          messageId: randomUUID(),
          type: UnifiedMessageType.CHANNEL_OPEN,
          sentAt: new Date().toISOString(),
          sessionId: this.sessionId,
          payload: {
            sessionId: this.sessionId,
            resumeFromSequence,
            // §15 rule 7: the token rides the payload, not the auth.
            token: this.offer.token,
          },
        }),
      );
      return await this.awaitOpened();
    } catch (err) {
      this.log.warn(
        `channel.open failed for session ${this.sessionId}: ` +
          (err instanceof Error ? err.message : String(err)),
      );
      await this.close("channel.open failed");
      return null;
    }
  }

  /** Await the channel.opened reply (bounded — the token is short-lived). */
  private awaitOpened(timeoutMs = 10_000): Promise<ChannelOpenedPayload | null> {
    return new Promise<ChannelOpenedPayload | null>((resolve) => {
      const timer = setTimeout(() => {
        cleanup();
        resolve(null);
      }, timeoutMs);
      const cleanup = (): void => {
        clearTimeout(timer);
        // Poll-free completion: the pending resolver is swapped for null.
        this.pendingOpened = null;
      };
      this.pendingOpened = (payload) => {
        cleanup();
        resolve(payload);
      };
    });
  }

  private pendingOpened:
    | ((payload: ChannelOpenedPayload | null) => void)
    | null = null;

  private async handleMessage(raw: string): Promise<void> {
    let frame: ParsedUnifiedFrame;
    try {
      frame = parseUnifiedFrame(raw);
    } catch (err) {
      this.log.warn("Dropping malformed channel frame:", err);
      return;
    }

    if (frame.type === UnifiedMessageType.CHANNEL_OPENED) {
      const payload = frame.payload as ChannelOpenedPayload;
      if (payload.sessionId !== this.sessionId) {
        this.log.warn(
          `channel.opened sessionId mismatch: ${payload.sessionId} (expected ${this.sessionId})`,
        );
        this.pendingOpened?.(null);
        return;
      }
      this.opened = payload;
      this.pendingOpened?.(payload);
      return;
    }

    if (frame.type === UnifiedMessageType.PROTOCOL_ERROR) {
      // Handshake rejection (expired/misbound token, invalid state) — the
      // bind never happened, so fall back cleanly.
      const payload = frame.payload as { code: string; message: string };
      if (!this.opened) {
        this.pendingOpened?.(null);
        return;
      }
      // Post-bind protocol errors ride the same dispatch as control frames.
      this.log.warn(
        `protocol.error on channel ${payload.code}: ${payload.message}`,
      );
      return;
    }

    // Any other frame post-bind (execution.*) is the engine's
    // channel-preferred arm — dispatch through the SAME pipeline the control
    // socket uses (shared dedupe, acks, handlers).
    await this.callbacks.onFrame(raw);
  }

  /** Send a raw frame on the channel socket. */
  send(raw: string): Promise<void> {
    return this.connection.send(raw);
  }

  /** Close the channel socket (idempotent; safe pre-bind). */
  async close(reason = "Session closed"): Promise<void> {
    this.pendingOpened?.(null);
    this.pendingOpened = null;
    await this.connection.close(reason);
  }
}

/** §7.5 (A1): the session-side collaborators the channel client needs.
 * Implemented by the SessionRegistry. */
export interface HostSessionLifecycle {
  /** The session's durable-event cursor — feeds channel.open's
   * resumeFromSequence (§12.3). 0 when unknown. */
  getHighestContiguousSequence(sessionId: string): number;
  /** Record a durable inbound frame's sequence (registry cursor tracking). */
  observeDurableSequence(sessionId: string, sequence: number): void;
  /** Record the bound channel socket after channel.opened. */
  bindChannel(
    sessionId: string,
    socket: unknown,
    opened: ChannelOpenedPayload,
  ): void;
  /** Mark the channel dead (§7.5: non-fatal — keep the session). */
  markChannelDead(sessionId: string): void;
  /** Drop the binding on session close (returns the socket to close). */
  unbindChannel(sessionId: string): { socket: unknown } | null;
}

/** State of the host-control FSM. */
export type HostControlState =
  | "IDLE"
  | "CONNECTING"
  | "OPEN"
  | "RECOVERING";

/** Handler for execution.start commands arriving from the engine. */
export interface ExecutionHandler {
  (frame: UnifiedFrame<"execution.start">): void | Promise<void>;
}

/** Handler for execution.cancel commands arriving from the engine. */
export interface ExecutionCancelHandler {
  (frame: UnifiedFrame<"execution.cancel">): void | Promise<void>;
}

/**
 * Production WebSocket connection for the host-control socket. Sends and
 * receives raw unified-protocol frames.
 */
class WebSocketHostControlConnection implements HostControlConnection {
  private readonly engineUrl: string;
  private readonly path: string;
  private ws: WebSocket | null = null;
  private running = false;

  onMessage?: (raw: string) => void | Promise<void>;
  onConnect?: () => void | Promise<void>;
  onDisconnect?: (code: number, reason: string) => void | Promise<void>;

  constructor(engineUrl: string, path: string) {
    this.engineUrl = engineUrl.replace(/\/+$/, "");
    this.path = path;
  }

  get isConnected(): boolean {
    return this.ws !== null && this.ws.readyState === WebSocket.OPEN;
  }

  connect(token: string): Promise<void> {
    const url = this.getWsUrl(token);
    return new Promise<void>((resolve, reject) => {
      const ws = new WebSocket(url, { perMessageDeflate: false });
      this.ws = ws;

      const onOpenError = (err: Error): void => {
        ws.off("open", onOpen);
        reject(err);
      };
      const onOpen = (): void => {
        ws.off("error", onOpenError);
        this.running = true;
        this.attachHandlers(ws);
        Promise.resolve(this.onConnect?.()).then(() => resolve(), reject);
      };

      ws.once("open", onOpen);
      ws.once("error", onOpenError);
    });
  }

  private attachHandlers(ws: WebSocket): void {
    ws.on("message", (raw: RawData) => {
      if (!this.running) {
        return;
      }
      void this.onMessage?.(raw.toString());
    });

    ws.on("close", (code: number, reasonBuf: Buffer) => {
      this.running = false;
      const reason = reasonBuf.toString();
      void this.onDisconnect?.(code, reason);
    });

    ws.on("error", (err: Error) => {
      if (this.running) {
        this.running = false;
        void this.onDisconnect?.(CloseCode.GOING_AWAY, err.message);
      }
    });
  }

  send(raw: string): Promise<void> {
    const ws = this.ws;
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      return Promise.reject(new Error("WebSocket not connected"));
    }
    return new Promise<void>((resolve, reject) => {
      ws.send(raw, (err) => (err ? reject(err) : resolve()));
    });
  }

  disconnect(reason = "Host shutdown"): Promise<void> {
    this.running = false;
    const ws = this.ws;
    if (ws && ws.readyState === WebSocket.OPEN) {
      try {
        ws.close(CloseCode.NORMAL, reason);
      } catch {
        // ignore
      }
    }
    this.ws = null;
    return Promise.resolve();
  }

  private getWsUrl(accessToken: string): string {
    const base = this.engineUrl.replace(/\/+$/, "");
    let wsBase: string;
    if (base.startsWith("https://")) {
      wsBase = "wss://" + base.slice("https://".length);
    } else if (base.startsWith("http://")) {
      wsBase = "ws://" + base.slice("http://".length);
    } else if (base.startsWith("ws://") || base.startsWith("wss://")) {
      wsBase = base;
    } else {
      wsBase = "ws://" + base;
    }
    return `${wsBase}${this.path}?token=${encodeURIComponent(accessToken)}`;
  }
}

/**
 * §7.5 (A1): raw WebSocket abstraction for the dedicated channel socket.
 * Same shape as {@link HostControlConnection} minus the reconnect machinery —
 * channel loss is non-fatal and reconnect is A2/Wave 5.
 */
export interface ChannelConnection {
  connect(token: string): Promise<void>;
  close(reason?: string): Promise<void>;
  send(raw: string): Promise<void>;
  readonly isOpen: boolean;
  onMessage?: (raw: string) => void | Promise<void>;
  onClose?: (code: number, reason: string) => void | Promise<void>;
}

/** Production channel socket for one session (§7.5). Same HOST_JWT
 * handshake gate as the control socket (`?token=`); the single-use channel
 * token rides the channel.open payload. */
export class WebSocketChannelConnection implements ChannelConnection {
  private readonly endpoint: string;
  private ws: WebSocket | null = null;
  private running = false;

  onMessage?: (raw: string) => void | Promise<void>;
  onClose?: (code: number, reason: string) => void | Promise<void>;

  constructor(endpoint: string) {
    this.endpoint = endpoint;
  }

  get isOpen(): boolean {
    return this.ws !== null && this.ws.readyState === WebSocket.OPEN;
  }

  connect(token: string): Promise<void> {
    // The token is the HOST_JWT handshake credential (same gate as the
    // control socket); `ws` carries it as the `?token=` query param.
    const url = new URL(this.endpoint);
    url.searchParams.set("token", token);
    return new Promise<void>((resolve, reject) => {
      const ws = new WebSocket(url.toString(), { perMessageDeflate: false });
      this.ws = ws;

      const onOpenError = (err: Error): void => {
        ws.off("open", onOpen);
        reject(err);
      };
      const onOpen = (): void => {
        ws.off("error", onOpenError);
        this.running = true;
        this.attachHandlers(ws);
        resolve();
      };

      ws.once("open", onOpen);
      ws.once("error", onOpenError);
    });
  }

  private attachHandlers(ws: WebSocket): void {
    ws.on("message", (raw: RawData) => {
      if (!this.running) {
        return;
      }
      void this.onMessage?.(raw.toString());
    });

    ws.on("close", (code: number, reasonBuf: Buffer) => {
      this.running = false;
      void this.onClose?.(code, reasonBuf.toString());
    });

    ws.on("error", (err: Error) => {
      if (this.running) {
        this.running = false;
        void this.onClose?.(CloseCode.GOING_AWAY, err.message);
      }
    });
  }

  send(raw: string): Promise<void> {
    const ws = this.ws;
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      return Promise.reject(new Error("Channel WebSocket not connected"));
    }
    return new Promise<void>((resolve, reject) => {
      ws.send(raw, (err) => (err ? reject(err) : resolve()));
    });
  }

  close(reason = "Session closed"): Promise<void> {
    this.running = false;
    const ws = this.ws;
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
      try {
        ws.close(CloseCode.NORMAL, reason);
      } catch {
        // ignore
      }
    }
    this.ws = null;
    return Promise.resolve();
  }
}

/** Initial reconnect backoff in ms (matches the legacy policy's 1s). */
const INITIAL_BACKOFF_MS = 1_000;
/** Maximum reconnect backoff in ms (matches the legacy policy's 32s). */
const MAX_BACKOFF_MS = 32_000;

/** Durable inbound frame families that must be acknowledged with protocol.ack. */
const DURABLE_INBOUND_TYPES: ReadonlySet<string> = new Set([
  UnifiedMessageType.EXECUTION_EVENT,
  UnifiedMessageType.EXECUTION_COMPLETE,
  UnifiedMessageType.EXECUTION_FAILED,
  UnifiedMessageType.EXECUTION_PAUSED,
  UnifiedMessageType.EXECUTION_CANCELLED,
  UnifiedMessageType.SESSION_CLOSED,
]);

/** §13 (A2): the host.reconcile payload shape as received from the engine. */
type HostReconcileWire = {
  hostInstanceId: string;
  decisions: ReconcileDecision[];
};

/** Inbound frame families handled by the client itself (not forwarded). */
const CLIENT_HANDLED_TYPES: ReadonlySet<string> = new Set([
  UnifiedMessageType.HOST_OPENED,
  UnifiedMessageType.HOST_HEARTBEAT,
  UnifiedMessageType.PROTOCOL_ERROR,
  // §12.3: the engine's protocol.ack for host→engine durable frames. The
  // engine replies on the socket the frame arrived on — with A1 that can be
  // the dedicated channel. The SDK records the ack's cursor for its own
  // replay bookkeeping later (A2); here it is simply expected noise.
  UnifiedMessageType.PROTOCOL_ACK,
]);

/** §7.5 (A1): outbound frame families that PREFER the dedicated channel
 * when one is bound for their session. Everything else (session.*, host.*,
 * protocol.ack, execution.accept/reject/start/cancel) is control-only — the
 * engine's channel arm refuses it (INVALID_MESSAGE). */
const CHANNEL_PREFERRED_OUTBOUND_TYPES: ReadonlySet<string> = new Set([
  UnifiedMessageType.EXECUTION_DELTA,
  UnifiedMessageType.EXECUTION_EVENT,
  UnifiedMessageType.EXECUTION_COMPLETE,
  UnifiedMessageType.EXECUTION_FAILED,
  UnifiedMessageType.EXECUTION_PAUSED,
]);

/** Simple append-only bounded LRU set for seen messageIds. */
class MessageIdDedupe {
  private readonly seen = new Set<string>();
  private readonly order: string[] = [];
  private readonly limit: number;

  constructor(limit: number) {
    this.limit = limit;
  }

  has(id: string): boolean {
    return this.seen.has(id);
  }

  add(id: string): void {
    if (this.seen.has(id)) {
      return;
    }
    this.seen.add(id);
    this.order.push(id);
    while (this.order.length > this.limit) {
      const removed = this.order.shift();
      if (removed) {
        this.seen.delete(removed);
      }
    }
  }
}

/**
 * Unified host-control client.
 */
export class HostControlClient {
  private readonly engineUrl: string;
  private readonly path: string;
  private readonly tokenProvider: HostTokenProvider;
  private readonly log: Logger;
  private readonly runtimeVersion: string;
  private readonly poolSize: number;
  private readonly capabilities: Record<string, unknown>;
  private readonly reportedCapacity: Record<string, unknown>;
  /** §3.7: the plugin's logged-in user id stamped onto host.open (LOCAL). */
  private readonly ownerUserId: string | undefined;

  private connection: HostControlConnection;
  private state: HostControlState = "IDLE";
  private running = false;
  private _currentNonce: string | null = null;
  private hostInstanceId: string | null = null;
  private heartbeatIntervalSeconds = 15;
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private effectivePoolSize = 0;
  private readonly seen: MessageIdDedupe;
  private executionHandler: ExecutionHandler | null = null;
  private executionCancelHandler: ExecutionCancelHandler | null = null;
  private sessionOpenObserver:
    | ((frame: UnifiedFrame<"session.open">) => void | Promise<void>)
    | null = null;
  private sessionCloseObserver:
    | ((frame: UnifiedFrame<"session.close">) => void | Promise<void>)
    | null = null;
  private readonly onPsk: ((psk: Uint8Array) => void) | undefined;
  /** §7.5 (A1): session collaborators (registry) for channel binding. */
  private readonly sessionLifecycle: HostSessionLifecycle | null;
  /** §7.5 (A1): channel socket factory override (tests inject a fake). */
  private readonly channelConnectionFactory:
    | ((endpoint: string) => ChannelConnection)
    | undefined;
  /** §7.5 (A1): one channel client per bound session (open handshake state). */
  private readonly channels = new Map<string, SessionChannelClient>();
  /** §13 (A2): retention collaborators (registry + cancel hook). */
  private readonly retention: HostRetentionLifecycle | null;
  /** §13 (A2): how long retained state survives a drop (ms). */
  private readonly retentionWindowMs: number;
  /** §13 (A2): the nonce of the instance the host last served (the resume
   * identity — a fresh host.open rotates `_currentNonce`, so it is captured
   * on every opened). */
  private previousInstanceNonce: string | null = null;
  /** §13 (A2): the id of the instance the host last served. */
  private previousHostInstanceId: string | null = null;
  /** §13 (A2): pending retention-expiry timer (armed on drop). */
  private retentionTimer: ReturnType<typeof setTimeout> | null = null;
  /** §13 (A2): guard so one resume runs per reconnection. */
  private resumePending: Promise<void> | null = null;
  /** §13 (A2): whether the current connection resumed (vs fresh host.open). */
  private resumedThisConnection = false;
  /**
   * §12.1/§13 (A2): per-session id of the last terminal/session frame the
   * host acknowledged (protocol.ack correlation) — the resume report's
   * lastAcknowledgedMessageId; the engine uses it to skip terminal resends
   * the host already holds.
   */
  private readonly lastAcknowledgedMessageIds = new Map<string, string>();

  constructor(options: HostControlClientOptions) {
    this.engineUrl = options.engineUrl.replace(/\/+$/, "");
    this.path = options.path ?? "/api/v1/agent/host/ws";
    this.tokenProvider = options.tokenProvider;
    this.log = options.logger ?? console;
    this.runtimeVersion = options.runtimeVersion ?? "0.1.0";
    this.poolSize = options.poolSize ?? 1;
    this.capabilities = options.capabilities ?? {};
    this.reportedCapacity = options.reportedCapacity ?? {};
    this.ownerUserId = options.ownerUserId;
    this.seen = new MessageIdDedupe(options.dedupeLimit ?? 256);
    this.onPsk = options.onPsk;
    this.sessionLifecycle = options.sessionLifecycle ?? null;
    this.channelConnectionFactory = options.channelConnectionFactory;
    this.retention = options.retention ?? null;
    this.retentionWindowMs = options.retentionWindowMs ?? 300_000;
    this.connection =
      options.connection ??
      new WebSocketHostControlConnection(this.engineUrl, this.path);
    this.wireConnection();
  }

  /** Current FSM state. */
  get currentState(): HostControlState {
    return this.state;
  }

  /** True once start() has been called and stop() has not. */
  get isRunning(): boolean {
    return this.running;
  }

  /** The hostInstanceId assigned by the engine on host.opened, or null. */
  get currentHostInstanceId(): string | null {
    return this.hostInstanceId;
  }

  /** The nonce used in the current or most recent host.open. */
  get currentNonce(): string | null {
    return this._currentNonce;
  }

  /** Register a callback for execution.start frames. */
  onExecutionStart(handler: ExecutionHandler): void {
    this.executionHandler = handler;
  }

  /** Register a callback for execution.cancel frames (§8.4: engine→host). */
  onExecutionCancel(handler: ExecutionCancelHandler): void {
    this.executionCancelHandler = handler;
  }

  /**
   * Observe `session.open` frames (§7.1): fired AFTER the transport-side
   * bookkeeping (channel bind, session.opened reply) so the consumer opens
   * its model/tools for the session. The payload is the raw frame — the
   * consumer narrows it.
   */
  onSessionOpen(
    handler: (frame: UnifiedFrame<"session.open">) => void | Promise<void>,
  ): void {
    this.sessionOpenObserver = handler;
  }

  /**
   * Observe `session.close` frames (§9): fired after the channel teardown.
   */
  onSessionClose(
    handler: (frame: UnifiedFrame<"session.close">) => void | Promise<void>,
  ): void {
    this.sessionCloseObserver = handler;
  }

  /**
   * Start the control socket: open with retry, then run the FSM until stop().
   *
   * The reconnect machinery (exponential backoff + the close-code reaction —
   * refresh on 4001, re-register on 4002, permanent stop on 4003, reconnect
   * otherwise) is INLINED here: the legacy `ReconnectingConnection` was its
   * only consumer and is deleted with the legacy wire.
   */
  async start(): Promise<void> {
    if (this.running) {
      throw new Error("HostControlClient already started");
    }
    this.running = true;
    this.state = "CONNECTING";

    this.log.info(`Host control connecting: ${this.engineUrl}${this.path}`);
    await this.connectWithRetry();
  }

  /** Connect, retrying with exponential backoff until success or `stopReconnecting()`. */
  private async connectWithRetry(): Promise<void> {
    this.backoffMs = INITIAL_BACKOFF_MS;
    while (this.shouldReconnect) {
      try {
        const token = await this.tokenProvider.getAccessToken();
        await this.connection.connect(token);
        return;
      } catch (err) {
        this.log.warn(
          "Host control connect failed; retrying:",
          err instanceof Error ? err.message : String(err),
        );
        await this.sleep(this.backoffMs);
        this.backoffMs = Math.min(this.backoffMs * 2, MAX_BACKOFF_MS);
      }
    }
    this.log.warn("Host control reconnect loop ended (stop requested)");
  }

  /**
   * React to a disconnect close code (REQ-A-002): refresh on 4001,
   * re-register on 4002 (and as the fallback when a refresh fails), stop on
   * 4003, reconnect otherwise.
   *
   * @returns true if the caller should attempt to reconnect.
   */
  private async handleDisconnectCode(code: number): Promise<boolean> {
    if (!this.shouldReconnect) {
      return false;
    }
    switch (code) {
      case CloseCode.NORMAL:
        return false;
      case CloseCode.TOKEN_EXPIRED:
        try {
          await this.tokenProvider.refreshTokens();
        } catch {
          // Refresh failed — fall back to a full re-register.
          await this.tokenProvider.reRegister();
        }
        return true;
      case CloseCode.INVALID_TOKEN:
        await this.tokenProvider.reRegister();
        return true;
      case CloseCode.AGENT_DEACTIVATED:
        this.shouldReconnect = false;
        return false;
      case CloseCode.DUPLICATE_CONNECTION:
        // Could be a race during a redeploy; still try to reconnect.
        return true;
      default:
        return true;
    }
  }

  /** Cease reconnection attempts (deliberate shutdown / protocol fatal). */
  private stopReconnecting(): void {
    this.shouldReconnect = false;
  }

  private shouldReconnect = true;
  private backoffMs = INITIAL_BACKOFF_MS;

  /** Sleep impl (fake timers in tests advance real setTimeout). */
  private sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  /** Stop the client: cease heartbeats, stop reconnecting, close the socket. */
  async stop(reason = "Host shutdown"): Promise<void> {
    this.running = false;
    this.stopHeartbeat();
    // §7.5: every bound channel socket dies with the host teardown.
    await this.closeAllChannels(reason);
    this.stopRetentionTimer();
    this.stopReconnecting();
    await this.connection?.disconnect(reason);
    this.state = "IDLE";
  }

  // ==================== Outbound sender API ====================

  /** Build and send a `protocol.ack` frame. */
  async sendProtocolAck(
    acknowledgedMessageId: string,
    highestContiguousSequence: number,
  ): Promise<void> {
    const payload: ProtocolAckPayload = {
      acknowledgedMessageId,
      highestContiguousSequence,
      status: "DURABLY_RECORDED",
    };
    await this.sendFrame({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: this.nextMessageId(),
      type: UnifiedMessageType.PROTOCOL_ACK,
      sentAt: new Date().toISOString(),
      correlationId: acknowledgedMessageId,
      sequence: null,
      payload,
    });
  }

  /** Accept a session.offer. */
  async sendSessionAccept(payload: SessionAcceptPayload): Promise<void> {
    await this.sendFrame({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: this.nextMessageId(),
      type: UnifiedMessageType.SESSION_ACCEPT,
      sentAt: new Date().toISOString(),
      sessionId: payload.sessionId,
      correlationId: payload.allocationId,
      payload,
    });
  }

  /** Reject a session.offer. */
  async sendSessionReject(payload: SessionRejectPayload): Promise<void> {
    await this.sendFrame({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: this.nextMessageId(),
      type: UnifiedMessageType.SESSION_REJECT,
      sentAt: new Date().toISOString(),
      sessionId: payload.sessionId,
      correlationId: payload.allocationId,
      payload,
    });
  }

  /** Confirm a session has been opened locally (session.opened reply). */
  async sendSessionOpened(payload: SessionOpenedPayload): Promise<void> {
    await this.sendFrame({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: this.nextMessageId(),
      type: UnifiedMessageType.SESSION_OPENED,
      sentAt: new Date().toISOString(),
      sessionId: payload.sessionId,
      payload,
    });
  }

  /** Reply to a session.close with session.closed. */
  async sendSessionClosed(payload: SessionClosedPayload): Promise<void> {
    await this.sendFrame({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: this.nextMessageId(),
      type: UnifiedMessageType.SESSION_CLOSED,
      sentAt: new Date().toISOString(),
      sessionId: payload.sessionId,
      payload,
    });
  }

  /** Acknowledge an execution.start with execution.accept. */
  async sendExecutionAccept(payload: ExecutionAcceptPayload): Promise<void> {
    await this.sendFrame({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: this.nextMessageId(),
      type: UnifiedMessageType.EXECUTION_ACCEPT,
      sentAt: new Date().toISOString(),
      sessionId: payload.executionId,
      executionId: payload.executionId,
      correlationId: payload.dispatchId,
      payload,
    });
  }

  /** Reject an execution.start with execution.reject. */
  async sendExecutionReject(payload: ExecutionRejectPayload): Promise<void> {
    await this.sendFrame({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: this.nextMessageId(),
      type: UnifiedMessageType.EXECUTION_REJECT,
      sentAt: new Date().toISOString(),
      executionId: payload.executionId,
      payload,
    });
  }

  /** Send a streaming execution.delta. */
  async sendExecutionDelta(payload: ExecutionDeltaPayload): Promise<void> {
    await this.sendFrame({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: this.nextMessageId(),
      type: UnifiedMessageType.EXECUTION_DELTA,
      sentAt: new Date().toISOString(),
      executionId: payload.executionId,
      payload,
    });
  }

  /** Send a durable execution.event. */
  async sendExecutionEvent(payload: ExecutionEventPayload): Promise<void> {
    await this.sendFrame({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: this.nextMessageId(),
      type: UnifiedMessageType.EXECUTION_EVENT,
      sentAt: new Date().toISOString(),
      executionId: payload.executionId,
      payload,
    });
  }

  /** Send terminal execution.complete. */
  async sendExecutionComplete(payload: ExecutionCompletePayload): Promise<void> {
    await this.sendFrame({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: this.nextMessageId(),
      type: UnifiedMessageType.EXECUTION_COMPLETE,
      sentAt: new Date().toISOString(),
      executionId: payload.executionId,
      payload,
    });
  }

  /** Send terminal execution.failed. */
  async sendExecutionFailed(payload: ExecutionFailedPayload): Promise<void> {
    await this.sendFrame({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: this.nextMessageId(),
      type: UnifiedMessageType.EXECUTION_FAILED,
      sentAt: new Date().toISOString(),
      executionId: payload.executionId,
      payload,
    });
  }

  /** Send execution.paused (HITL / checkpoint). */
  async sendExecutionPaused(payload: ExecutionPausedPayload): Promise<void> {
    await this.sendFrame({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: this.nextMessageId(),
      type: UnifiedMessageType.EXECUTION_PAUSED,
      sentAt: new Date().toISOString(),
      executionId: payload.executionId,
      payload,
    });
  }

  /** Send a terminal execution.cancelled (host aborted the execution). */
  async sendExecutionCancelled(
    payload: ExecutionCancelledPayload,
  ): Promise<void> {
    await this.sendFrame({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: this.nextMessageId(),
      type: UnifiedMessageType.EXECUTION_CANCELLED,
      sentAt: new Date().toISOString(),
      executionId: payload.executionId,
      payload,
    });
  }

  /** Answer a rejected frame with protocol.error (§8.7 etc.). */
  async sendProtocolError(payload: ProtocolErrorPayload): Promise<void> {
    await this.sendFrame({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: this.nextMessageId(),
      type: UnifiedMessageType.PROTOCOL_ERROR,
      sentAt: new Date().toISOString(),
      correlationId: payload.offendingMessageId ?? null,
      payload,
    });
  }

  /** Request cancellation of an execution. */
  async sendExecutionCancel(payload: ExecutionCancelPayload): Promise<void> {
    await this.sendFrame({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: this.nextMessageId(),
      type: UnifiedMessageType.EXECUTION_CANCEL,
      sentAt: new Date().toISOString(),
      executionId: payload.executionId,
      payload,
    });
  }

  /** Send a durable HITL approval request (execution.approval.requested). */
  async sendExecutionApprovalRequested(
    payload: ExecutionApprovalRequestedPayload,
  ): Promise<void> {
    await this.sendFrame({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: this.nextMessageId(),
      type: UnifiedMessageType.EXECUTION_APPROVAL_REQUESTED,
      sentAt: new Date().toISOString(),
      executionId: payload.executionId,
      payload,
    });
  }

  /** Advertise capacity change (host.capacity). */
  async sendHostCapacity(payload: HostCapacityPayload): Promise<void> {
    await this.sendFrame({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: this.nextMessageId(),
      type: UnifiedMessageType.HOST_CAPACITY,
      sentAt: new Date().toISOString(),
      payload,
    });
  }

  /** Direct send of an already-built outbound frame. */
  async sendFrame(frame: ParsedUnifiedFrame): Promise<void> {
    const fullFrame: ParsedUnifiedFrame = {
      ...frame,
      sequence: frame.sequence ?? null,
      correlationId: frame.correlationId ?? null,
      hostInstanceId: frame.hostInstanceId ?? null,
      sessionId: frame.sessionId ?? null,
      executionId: frame.executionId ?? null,
    };
    // §7.5 (A1): execution.delta/event/terminal PREFER the bound channel
    // socket for their session; everything else is control-only. A channel
    // send failure falls back to the control socket (best-effort) — channel
    // loss must not break an execution.
    const channel = this.channelForOutbound(fullFrame);
    if (channel) {
      try {
        await channel.send(encodeUnifiedFrame(fullFrame));
        // Terminal frames end the execution — drop its session mapping.
        if (
          fullFrame.type === UnifiedMessageType.EXECUTION_COMPLETE ||
          fullFrame.type === UnifiedMessageType.EXECUTION_FAILED ||
          fullFrame.type === UnifiedMessageType.EXECUTION_PAUSED
        ) {
          this.executionSessions.delete(fullFrame.executionId ?? "");
        }
        return;
      } catch (err) {
        this.log.warn(
          "Channel send failed; falling back to the control socket:",
          err instanceof Error ? err.message : String(err),
        );
      }
    }
    await this.connection.send(encodeUnifiedFrame(fullFrame));
  }

  /**
   * §7.5 (A1): resolve the outbound channel for an execution frame — the
   * bound, alive channel for the frame's session, else null (control).
   * Resolves the session from the envelope's sessionId, falling back to the
   * executionId→sessionId map the client fills on execution.start.
   */
  private channelForOutbound(frame: ParsedUnifiedFrame): SessionChannelClient | null {
    if (!CHANNEL_PREFERRED_OUTBOUND_TYPES.has(frame.type)) {
      return null;
    }
    const sessionId =
      frame.sessionId ?? this.executionSessions.get(frame.executionId ?? "");
    if (!sessionId) {
      return null;
    }
    const channel = this.channels.get(sessionId);
    return channel && channel.isOpen ? channel : null;
  }

  /** Registry of executionId → sessionId, filled from execution.start. */
  private readonly executionSessions = new Map<string, string>();

  // ==================== Internal transport wiring ====================

  private wireConnection(): void {
    this.connection.onConnect = () => this.onConnect();
    this.connection.onDisconnect = (code, reason) =>
      this.onDisconnect(code, reason);
    this.connection.onMessage = (raw) => this.handleRaw(raw);
  }

  private async onConnect(): Promise<void> {
    this.state = "CONNECTING";
    this.hostInstanceId = null;
    this._currentNonce = randomUUID();
    await this.sendHostOpen();
  }

  private async sendHostOpen(): Promise<void> {
    const payload: HostOpenPayload = {
      instanceNonce: this._currentNonce ?? randomUUID(),
      hostname: hostname(),
      runtimeVersion: this.runtimeVersion,
      supportedProtocolVersions: [SUPPORTED_PROTOCOL_VERSION],
      poolSize: this.poolSize,
      capabilities: this.capabilities,
      reportedCapacity: this.reportedCapacity,
      // §3.7 local-owner model: present only for LOCAL/plugin hosts —
      // spreading undefined keeps the field off the MANAGED wire.
      ...(this.ownerUserId !== undefined ? { ownerUserId: this.ownerUserId } : {}),
    };
    await this.sendFrame({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: this.nextMessageId(),
      type: UnifiedMessageType.HOST_OPEN,
      sentAt: new Date().toISOString(),
      payload,
    });
  }

  private async onDisconnect(code: number, reason: string): Promise<void> {
    this.log.info(`Host control socket closed: code=${code} reason=${reason}`);
    this.stopHeartbeat();
    // §13 (A2): capture the resume identity BEFORE it is cleared — a
    // reconnecting host reports (previousHostInstanceId, instanceNonce).
    this.previousHostInstanceId = this.hostInstanceId;
    this.previousInstanceNonce = this._currentNonce;
    this.hostInstanceId = null;
    this.state = this.running ? "RECOVERING" : "IDLE";

    // §13 (A2): a drop no longer tears the world down. Sessions, execution
    // states, the durable-event cursors and the PSK vaults are RETAINED for
    // the window pending host.resume/host.reconcile; teardown happens only
    // on a CLOSE decision, a protocol.error reject, or retention expiry.
    // Bound channel sockets are INDEPENDENT transports — where one is still
    // alive (and the engine still routes to it) the §7.5 binding stands and
    // is resumed as-is; one that died with the engine flows through the
    // existing onDead path (non-fatal, session rides the control socket).
    if (this.running && this.retention && this.previousHostInstanceId) {
      this.retention.markAllDisconnected();
      this.armRetentionTimer();
      this.resumedThisConnection = false;
    }

    if (!this.running) {
      return;
    }

    const shouldReconnect = await this.handleDisconnectCode(code);
    if (shouldReconnect && this.running) {
      await this.connectWithRetry();
    }
  }

  // ==================== §13 retention (A2) ====================

  /**
   * Arm the retention-expiry timer: if no successful resume happens within
   * the window the retained state is torn down (the engine expired its side
   * too — HOST_LOST).
   */
  private armRetentionTimer(): void {
    this.stopRetentionTimer();
    if (!this.retention) {
      return;
    }
    this.retentionTimer = setTimeout(() => {
      void this.expireRetention();
    }, this.retentionWindowMs);
  }

  private stopRetentionTimer(): void {
    if (this.retentionTimer) {
      clearTimeout(this.retentionTimer);
      this.retentionTimer = null;
    }
  }

  /**
   * §13: the retention window lapsed without a reconnect — clear retained
   * state and tear the sessions down (the engine expired its side too).
   */
  private async expireRetention(): Promise<void> {
    const retention = this.retention;
    if (!retention) {
      return;
    }
    this.stopRetentionTimer();
    const expired = retention.retainedSessionIds();
    if (expired.length === 0) {
      return;
    }
    this.log.info(
      `Retention window expired for ${expired.length} session(s) — tearing down (HOST_LOST)`,
    );
    for (const sessionId of expired) {
      retention.closeRetained(sessionId);
    }
  }

  /**
   * §13: after host.opened on a reconnected socket, if retained state exists
   * report it with host.resume and apply the engine's host.reconcile
   * decisions. A rejected resume (protocol.error — no matching RECOVERING
   * instance) falls back to the fresh-host path and wipes retained state.
   * Runs once per reconnection; never throws.
   */
  private async attemptResume(): Promise<void> {
    const retention = this.retention;
    if (
      !retention ||
      this.resumedThisConnection ||
      this.resumePending ||
      !this.previousHostInstanceId ||
      !this.previousInstanceNonce
    ) {
      return;
    }
    const summaries = retention.buildRetainedSummaries();
    if (summaries.length === 0) {
      this.stopRetentionTimer();
      this.resumedThisConnection = true;
      return;
    }
    this.resumePending = this.runResume(summaries).finally(() => {
      this.resumePending = null;
    });
    await this.resumePending;
  }

  /** Send host.resume, await host.reconcile (bounded), apply decisions. */
  private async runResume(
    summaries: Array<{
      sessionId: string;
      state: string;
      capacityHeld: boolean;
    }>,
  ): Promise<void> {
    const retention = this.retention;
    if (!retention) {
      return;
    }
    const payload: HostResumePayload = {
      previousHostInstanceId: this.previousHostInstanceId ?? "",
      instanceNonce: this.previousInstanceNonce ?? "",
      sessions: summaries.map((s) => ({
        sessionId: s.sessionId,
        state: s.state,
        capacityHeld: s.capacityHeld,
        activeExecutionId: null,
        lastSentSequence: retention.getHighestContiguousSequence(s.sessionId),
        lastAcknowledgedMessageId: this.lastAcknowledgedMessageIds.get(s.sessionId) ?? null,
      })),
    };
    this.log.info(
      `host.resume: ${summaries.length} retained session(s) ` +
        `(previous instance ${this.previousHostInstanceId})`,
    );
    this.resumedThisConnection = true;
    try {
      await this.sendFrame({
        protocolVersion: SUPPORTED_PROTOCOL_VERSION,
        messageId: this.nextMessageId(),
        type: UnifiedMessageType.HOST_RESUME,
        sentAt: new Date().toISOString(),
        payload,
      });
    } catch (err) {
      this.log.error(
        "host.resume send failed:",
        err instanceof Error ? err.message : String(err),
      );
      // The drop-retry machinery redials; retention stays armed.
      this.resumedThisConnection = false;
      return;
    }

    const reply = await this.awaitReconcile();
    if (!reply) {
      // Bounded await lapsed: the engine never answered (its side expired or
      // was replaced). A subsequent protocol.error still wipes state; here
      // the window keeps running so a later reconnect can retry.
      this.log.warn("host.reconcile not received in time; retention stays armed");
      return;
    }
    await this.applyReconcile(reply.payload as HostReconcileWire);
  }

  /**
   * Await the host.reconcile reply (bounded, correlation on messageId).
   * @returns the parsed host.reconcile frame, or null on timeout/error.
   */
  private awaitReconcile(timeoutMs = 10_000): Promise<UnifiedFrame<"host.reconcile"> | null> {
    return new Promise<UnifiedFrame<"host.reconcile"> | null>((resolve) => {
      const timer = setTimeout(() => {
        this.pendingReconcile = null;
        resolve(null);
      }, timeoutMs);
      this.pendingReconcile = (frame) => {
        clearTimeout(timer);
        this.pendingReconcile = null;
        resolve(frame);
      };
    });
  }

  private pendingReconcile:
    | ((frame: UnifiedFrame<"host.reconcile">) => void)
    | null = null;

  /**
   * Apply the engine's authoritative decisions (§13):
   *  - KEEP: re-bind the session; the engine replays its durable events onto
   *    the resumed socket where the existing dispatch (dedupe by messageId,
   *    registry cursor) absorbs them; a fresh channel.open re-binds the
   *    dedicated channel with the retained cursor.
   *  - CANCEL_EXECUTION: the decision IS the cancellation command — the
   *    hook aborts the in-flight turn and the normal execution.cancelled
   *    terminal flows out; NO separate execution.cancel frame is sent.
   *  - CLOSE/unknown: drop the session (registry removal, vault clear).
   */
  private async applyReconcile(
    payload: {
      hostInstanceId: string;
      decisions: Array<{
        sessionId: string;
        action: string;
        resumeFromSequence?: number | null;
        executionId?: string | null;
        reasonCode?: string | null;
      }>;
    },
  ): Promise<void> {
    const retention = this.retention;
    if (!retention) {
      return;
    }
    // The engine re-adopted this instance — the retention window closes.
    this.stopRetentionTimer();
    for (const decision of payload.decisions as ReconcileDecision[]) {
      const sessionId = decision.sessionId;
      if (decision.action === ReconcileAction.KEEP) {
        const cursor = retention.rebindAfterReconcile(sessionId);
        this.log.info(
          `Reconcile KEEP for session ${sessionId} (cursor ${cursor}, ` +
            `resume from ${decision.resumeFromSequence ?? "n/a"})`,
        );
        continue;
      }
      if (decision.action === ReconcileAction.CANCEL_EXECUTION) {
        const executionId = decision.executionId ?? "";
        this.log.info(
          `Reconcile CANCEL_EXECUTION for session ${sessionId} ` +
            `(execution ${executionId}) — applying execution.cancel semantics`,
        );
        retention.cancelExecution(executionId);
        retention.closeRetained(sessionId);
        continue;
      }
      // CLOSE (and any unknown action — fail closed toward teardown).
      this.log.info(
        `Reconcile ${decision.action} for session ${sessionId} ` +
          `(reason ${decision.reasonCode ?? "n/a"}) — dropping session`,
      );
      retention.closeRetained(sessionId);
    }
  }

  private async handleRaw(raw: string): Promise<void> {
    let frame: ParsedUnifiedFrame;
    try {
      frame = parseUnifiedFrame(raw);
    } catch (err) {
      this.log.warn("Dropping malformed unified frame:", err);
      return;
    }

    const messageId = frame.messageId;

    if (this.seen.has(messageId)) {
      if (DURABLE_INBOUND_TYPES.has(frame.type)) {
        await this.ack(frame);
      }
      return;
    }
    this.seen.add(messageId);

    if (frame.type === UnifiedMessageType.HOST_OPENED) {
      await this.handleHostOpened(frame as UnifiedFrame<"host.opened">);
      return;
    }

    if (frame.type === UnifiedMessageType.HOST_RECONCILE) {
      // §13 (A2): the engine's authoritative answer to host.resume.
      this.pendingReconcile?.(frame as UnifiedFrame<"host.reconcile">);
      return;
    }

    if (frame.type === UnifiedMessageType.PROTOCOL_ERROR) {
      await this.handleProtocolError(frame);
      return;
    }

    if (frame.type === UnifiedMessageType.HOST_HEARTBEAT) {
      // Heartbeat from engine is a keep-alive; no host action required.
      return;
    }

    if (frame.type === UnifiedMessageType.PROTOCOL_ACK) {
      // §12.3: the engine's ack for a host→engine durable frame (arrives on
      // the socket the frame was sent on — control or channel). Bookkeeping
      // of the acked cursor is A2; here it is simply expected noise.
      return;
    }

    if (DURABLE_INBOUND_TYPES.has(frame.type)) {
      // §7.5 (A1): feed the registry's durable cursor (monotonic) so a later
      // channel.open carries the correct resumeFromSequence, then ack.
      if (frame.sessionId && typeof frame.sequence === "number") {
        this.sessionLifecycle?.observeDurableSequence(
          frame.sessionId,
          frame.sequence,
        );
      }
      // §12.1/§13 (A2): record the per-session last-acknowledged messageId
      // so host.resume can report it (engine terminal-resend dedup).
      if (frame.sessionId) {
        this.lastAcknowledgedMessageIds.set(frame.sessionId, messageId);
      }
      await this.ack(frame);
    }

    if (frame.type === UnifiedMessageType.SESSION_OFFER) {
      await this.handleSessionOffer(frame as UnifiedFrame<"session.offer">);
      return;
    }
    if (frame.type === UnifiedMessageType.SESSION_OPEN) {
      await this.handleSessionOpen(frame as UnifiedFrame<"session.open">);
      return;
    }
    if (frame.type === UnifiedMessageType.SESSION_CLOSE) {
      await this.handleSessionClose(frame as UnifiedFrame<"session.close">);
      return;
    }
    if (frame.type === UnifiedMessageType.EXECUTION_START) {
      await this.handleExecutionStart(frame as UnifiedFrame<"execution.start">);
      return;
    }
    if (frame.type === UnifiedMessageType.EXECUTION_CANCEL) {
      await this.handleExecutionCancel(frame as UnifiedFrame<"execution.cancel">);
      return;
    }

    if (!CLIENT_HANDLED_TYPES.has(frame.type)) {
      this.log.warn("Unhandled unified frame type:", frame.type);
    }
  }

  private async handleHostOpened(
    frame: UnifiedFrame<"host.opened">,
  ): Promise<void> {
    const payload = frame.payload as HostOpenedPayload;
    // §13 (A2): a reconnection (retained state + a previous instance) runs
    // the resume handshake INSTEAD of treating this as a fresh world. The
    // fresh-host path is the protocol.error fallback.
    const isReconnect = this.previousHostInstanceId !== null && this.retention !== null;
    this.hostInstanceId = payload.hostInstanceId;
    this.effectivePoolSize = payload.effectivePoolSize;
    this.heartbeatIntervalSeconds = payload.heartbeatIntervalSeconds;
    this.state = "OPEN";
    this.startHeartbeat();
    this.log.info(
      `Host opened: instance=${payload.hostInstanceId} ` +
        `pool=${payload.effectivePoolSize} heartbeat=${payload.heartbeatIntervalSeconds}s`,
    );
    if (isReconnect) {
      // Retained state exists — report it before new work flows. A rejected
      // resume (protocol.error) wipes state; the fresh host.open fallback
      // re-opens the instance and new work proceeds there.
      await this.attemptResume();
    }
    // PSK receive path (design §6/§10): decode, validate, keep in process
    // memory ONLY, hand to the SessionRegistry via the callback. The value
    // is never logged and never persisted; the frame-logger denylist keeps
    // it out of captured logs on the engine side.
    if (payload.psk) {
      const pskBytes = Buffer.from(payload.psk, "base64");
      if (pskBytes.length !== 32) {
        this.log.error(
          `host.opened carried a malformed PSK (${pskBytes.length} bytes, expected 32) — keyless delivery disabled for this run`,
        );
      } else {
        try {
          this.onPsk?.(pskBytes);
        } catch (err) {
          this.log.error(
            "onPsk handler failed:",
            err instanceof Error ? err.message : String(err),
          );
        }
      }
    }
  }

  private async handleProtocolError(frame: ParsedUnifiedFrame): Promise<void> {
    const payload = frame.payload as {
      code: string;
      message: string;
      retryable: boolean;
    };
    this.log.warn(
      `protocol.error ${payload.code}: ${payload.message} (retryable=${payload.retryable})`,
    );
    if (payload.code === ProtocolErrorCode.UNSUPPORTED_VERSION) {
      this.log.error("Unsupported protocol version — closing connection");
      this.stopReconnecting();
      await this.connection.disconnect("Unsupported protocol version");
      this.state = "IDLE";
      return;
    }
    // §13 (A2): the engine rejected host.resume (no matching RECOVERING
    // instance — expired/unknown/nonce mismatch, SESSION_NOT_FOUND or
    // IDENTITY_MISMATCH). Fall back to a fresh host.open: wipe the retained
    // state (the engine has no instance — it is stale), close the window,
    // and let the reconnection machinery re-dial into the fresh path.
    if (
      this.running &&
      this.retention &&
      this.resumedThisConnection &&
      (payload.code === ProtocolErrorCode.SESSION_NOT_FOUND ||
        payload.code === ProtocolErrorCode.IDENTITY_MISMATCH)
    ) {
      this.log.warn(
        `host.resume rejected (${payload.code}) — wiping retained state, falling back to fresh host.open`,
      );
      this.stopRetentionTimer();
      this.resumedThisConnection = false;
      this.previousHostInstanceId = null;
      this.previousInstanceNonce = null;
      const retained = this.retention.retainedSessionIds();
      for (const sessionId of retained) {
        this.retention.closeRetained(sessionId);
      }
      this.lastAcknowledgedMessageIds.clear();
    }
  }

  private async handleSessionOffer(
    frame: UnifiedFrame<"session.offer">,
  ): Promise<void> {
    const payload = frame.payload as SessionOfferPayload;
    this.log.info(
      `Session offered: ${payload.sessionId} kind=${payload.kind}`,
    );
    // Default accept policy: accept all offers. A higher layer may override
    // by registering a custom handler via onExecutionStart, but session offers
    // are currently auto-accepted here.
    await this.sendSessionAccept({
      allocationId: payload.allocationId,
      sessionId: payload.sessionId,
      acceptedAt: new Date().toISOString(),
    });
  }

  private async handleSessionOpen(
    frame: UnifiedFrame<"session.open">,
  ): Promise<void> {
    const payload = frame.payload as SessionOpenPayload;
    const sessionId = frame.sessionId ?? payload.sessionId;
    this.log.info(`Session opened by engine: ${sessionId}`);

    // §7.5 (A1): the engine minted a dedicated-channel offer — open the
    // channel socket and run the bind handshake BEFORE confirming the open.
    // A failed handshake is non-fatal: the session rides the control socket
    // (the engine accepts execution arms on both sockets).
    let channelMode = "CONTROL";
    const offer = payload.channel ?? null;
    if (offer) {
      const opened = await this.openChannel(sessionId, offer);
      if (opened) {
        channelMode = "CHANNEL";
      }
    }

    await this.sendSessionOpened({
      sessionId,
      ready: true,
      channelMode,
    });
    // Consumer hook (§7.1): after the transport-side bookkeeping the
    // supervisor/worker opens its model + tools for the session.
    if (this.sessionOpenObserver) {
      await this.sessionOpenObserver(frame);
    }
  }

  // ==================== Dedicated session channel (§7.5, A1) ====================

  /**
   * Open the dedicated channel socket for a session and run the
   * channel.open → channel.opened handshake (§7.5). Auth is the same
   * HOST_JWT handshake gate as the control socket; the single-use token
   * rides the channel.open payload. Never throws — any failure logs a warn
   * and falls back to the control socket.
   *
   * @returns the channel.opened payload on success, null on any failure.
   */
  private async openChannel(
    sessionId: string,
    offer: SessionChannelOffer,
  ): Promise<ChannelOpenedPayload | null> {
    const lifecycle = this.sessionLifecycle;
    if (!lifecycle) {
      this.log.warn(
        `Channel offer for session ${sessionId} ignored: no session lifecycle wired`,
      );
      return null;
    }
    if (this.channels.has(sessionId)) {
      this.log.warn(
        `Channel offer for session ${sessionId} ignored: channel already exists`,
      );
      return null;
    }

    const channel = new SessionChannelClient(
      sessionId,
      offer,
      () => this.tokenProvider.getAccessToken(),
      {
        onFrame: (raw) => this.handleRaw(raw),
        onDead: (reason) => {
          this.log.warn(
            `Dedicated channel lost for session ${sessionId} (${reason}): ` +
              "session unaffected — traffic continues on the control socket",
          );
          lifecycle.markChannelDead(sessionId);
          this.channels.delete(sessionId);
        },
      },
      this.log,
      this.channelConnectionFactory,
    );

    const opened = await channel.open(lifecycle.getHighestContiguousSequence(sessionId));
    if (!opened) {
      return null;
    }
    this.channels.set(sessionId, channel);
    lifecycle.bindChannel(sessionId, channel, opened);
    this.log.info(
      `Channel bound: session=${sessionId} cursor=${opened.highestContiguousSequence}`,
    );
    return opened;
  }

  /** Close a session's channel socket alongside the control teardown (§7.5). */
  private async closeChannel(sessionId: string, reason: string): Promise<void> {
    const channel = this.channels.get(sessionId);
    this.channels.delete(sessionId);
    if (channel) {
      await channel.close(reason);
    }
    this.sessionLifecycle?.unbindChannel(sessionId);
    // Drop the session's execution mappings — they resolve to a channel
    // that no longer exists.
    for (const [executionId, mapped] of this.executionSessions) {
      if (mapped === sessionId) {
        this.executionSessions.delete(executionId);
      }
    }
  }

  /** Close every channel socket (host stop / control teardown). */
  private async closeAllChannels(reason: string): Promise<void> {
    for (const sessionId of [...this.channels.keys()]) {
      await this.closeChannel(sessionId, reason);
    }
  }

  private async handleSessionClose(
    frame: UnifiedFrame<"session.close">,
  ): Promise<void> {
    const payload = frame.payload as SessionClosedPayload;
    const sessionId = frame.sessionId ?? payload.sessionId;
    this.log.info(`Session close requested: ${sessionId}`);
    // §7.5: the channel dies with the session.
    await this.closeChannel(sessionId, "Session closed");
    await this.sendSessionClosed({
      sessionId,
      closedAt: new Date().toISOString(),
      reasonCode: payload.reasonCode ?? "HOST_INITIATED",
    });
    // Consumer hook (§9): the worker/registry tears the session down
    // alongside the transport-side teardown.
    if (this.sessionCloseObserver) {
      await this.sessionCloseObserver(frame);
    }
  }

  private async handleExecutionStart(
    frame: UnifiedFrame<"execution.start">,
  ): Promise<void> {
    const payload = frame.payload as ExecutionEventPayload;
    const executionId = frame.executionId ?? payload.executionId;
    this.log.info(`Execution start: ${executionId}`);
    // §7.5 (A1): remember the session so channel-preferred sends (which
    // carry only executionId) can resolve their channel.
    const startSessionId =
      frame.sessionId ??
      (frame.payload as { sessionId?: string | null }).sessionId ??
      null;
    if (executionId && startSessionId) {
      this.executionSessions.set(executionId, startSessionId);
    }
    if (this.executionHandler) {
      await this.executionHandler(frame);
    }
  }

  private async handleExecutionCancel(
    frame: UnifiedFrame<"execution.cancel">,
  ): Promise<void> {
    const payload = frame.payload as ExecutionCancelPayload;
    const executionId = frame.executionId ?? payload.executionId;
    this.log.info(`Execution cancel requested: ${executionId}`);
    if (this.executionCancelHandler) {
      await this.executionCancelHandler(frame);
      return;
    }
    this.log.warn(
      `execution.cancel for ${executionId} dropped: no cancel handler wired`,
    );
  }

  private async ack(frame: ParsedUnifiedFrame): Promise<void> {
    await this.sendProtocolAck(
      frame.messageId,
      frame.sequence ?? 0,
    );
  }

  // ==================== Heartbeat ====================

  private startHeartbeat(): void {
    this.stopHeartbeat();
    const intervalMs = Math.max(1_000, this.heartbeatIntervalSeconds * 1000);
    this.heartbeatTimer = setInterval(() => {
      void this.sendHeartbeat();
    }, intervalMs);
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  private async sendHeartbeat(): Promise<void> {
    if (this.state !== "OPEN") {
      return;
    }
    const payload: HostHeartbeatPayload = {
      observedAt: new Date().toISOString(),
      effectivePoolSize: this.effectivePoolSize,
      activeSessionCount: 0,
      pendingOfferCount: 0,
      health: "HEALTHY",
    };
    await this.sendFrame({
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: this.nextMessageId(),
      type: UnifiedMessageType.HOST_HEARTBEAT,
      sentAt: new Date().toISOString(),
      hostInstanceId: this.hostInstanceId ?? undefined,
      sequence: null,
      payload,
    });
  }

  // ==================== Helpers ====================

  private nextMessageId(): string {
    return randomUUID();
  }
}
