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
  SUPPORTED_PROTOCOL_VERSION,
  type ExecutionAcceptPayload,
  type ExecutionCancelPayload,
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
  type ParsedUnifiedFrame,
  type ProtocolAckPayload,
  type SessionAcceptPayload,
  type SessionClosedPayload,
  type SessionOfferPayload,
  type SessionRejectPayload,
  type SessionOpenedPayload,
  type UnifiedFrame,
} from "../protocol/unifiedFrames.js";
import { randomUUID } from "node:crypto";
import { hostname } from "node:os";
import type { Logger } from "../models/index.js";
import { WebSocket, type RawData } from "ws";
import type { ReconnectingConnection } from "../transport/reconnectingConnection.js";

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

/** Durable inbound frame families that must be acknowledged with protocol.ack. */
const DURABLE_INBOUND_TYPES: ReadonlySet<string> = new Set([
  UnifiedMessageType.EXECUTION_EVENT,
  UnifiedMessageType.EXECUTION_COMPLETE,
  UnifiedMessageType.EXECUTION_FAILED,
  UnifiedMessageType.EXECUTION_PAUSED,
  UnifiedMessageType.EXECUTION_CANCELLED,
  UnifiedMessageType.SESSION_CLOSED,
]);

/** Inbound frame families handled by the client itself (not forwarded). */
const CLIENT_HANDLED_TYPES: ReadonlySet<string> = new Set([
  UnifiedMessageType.HOST_OPENED,
  UnifiedMessageType.HOST_HEARTBEAT,
  UnifiedMessageType.PROTOCOL_ERROR,
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

  private connection: HostControlConnection;
  private reconnecting: ReconnectingConnection | null = null;
  private state: HostControlState = "IDLE";
  private running = false;
  private _currentNonce: string | null = null;
  private hostInstanceId: string | null = null;
  private heartbeatIntervalSeconds = 15;
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private effectivePoolSize = 0;
  private readonly seen: MessageIdDedupe;
  private executionHandler: ExecutionHandler | null = null;
  private readonly onPsk: ((psk: Uint8Array) => void) | undefined;

  constructor(options: HostControlClientOptions) {
    this.engineUrl = options.engineUrl.replace(/\/+$/, "");
    this.path = options.path ?? "/api/v1/agent/host/ws";
    this.tokenProvider = options.tokenProvider;
    this.log = options.logger ?? console;
    this.runtimeVersion = options.runtimeVersion ?? "0.1.0";
    this.poolSize = options.poolSize ?? 1;
    this.capabilities = options.capabilities ?? {};
    this.reportedCapacity = options.reportedCapacity ?? {};
    this.seen = new MessageIdDedupe(options.dedupeLimit ?? 256);
    this.onPsk = options.onPsk;
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

  /**
   * Start the control socket: open with retry, then run the FSM until stop().
   */
  async start(): Promise<void> {
    if (this.running) {
      throw new Error("HostControlClient already started");
    }
    this.running = true;
    this.state = "CONNECTING";

    const { ReconnectingConnection: RC } = await import(
      "../transport/reconnectingConnection.js"
    );
    this.reconnecting = new RC({
      connection: this.connection as unknown as import("../transport/connection.js").WebSocketConnection,
      getAccessToken: () => this.tokenProvider.getAccessToken(),
      refreshToken: () => this.tokenProvider.refreshTokens(),
      register: () => this.tokenProvider.reRegister(),
    });

    await this.reconnecting.connectWithRetry();
  }

  /** Stop the client: cease heartbeats, stop reconnecting, close the socket. */
  async stop(reason = "Host shutdown"): Promise<void> {
    this.running = false;
    this.stopHeartbeat();
    this.reconnecting?.stop();
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
    await this.connection.send(encodeUnifiedFrame(fullFrame));
  }

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
    this.hostInstanceId = null;
    this.state = this.running ? "RECOVERING" : "IDLE";

    if (!this.running || !this.reconnecting) {
      return;
    }

    const shouldReconnect = await this.reconnecting.handleDisconnect(
      code,
      reason,
    );
    if (shouldReconnect && this.running) {
      await this.reconnecting.connectWithRetry();
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

    if (frame.type === UnifiedMessageType.PROTOCOL_ERROR) {
      await this.handleProtocolError(frame);
      return;
    }

    if (frame.type === UnifiedMessageType.HOST_HEARTBEAT) {
      // Heartbeat from engine is a keep-alive; no host action required.
      return;
    }

    if (DURABLE_INBOUND_TYPES.has(frame.type)) {
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

    if (!CLIENT_HANDLED_TYPES.has(frame.type)) {
      this.log.warn("Unhandled unified frame type:", frame.type);
    }
  }

  private async handleHostOpened(
    frame: UnifiedFrame<"host.opened">,
  ): Promise<void> {
    const payload = frame.payload as HostOpenedPayload;
    this.hostInstanceId = payload.hostInstanceId;
    this.effectivePoolSize = payload.effectivePoolSize;
    this.heartbeatIntervalSeconds = payload.heartbeatIntervalSeconds;
    this.state = "OPEN";
    this.startHeartbeat();
    this.log.info(
      `Host opened: instance=${payload.hostInstanceId} ` +
        `pool=${payload.effectivePoolSize} heartbeat=${payload.heartbeatIntervalSeconds}s`,
    );
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
      this.reconnecting?.stop();
      await this.connection.disconnect("Unsupported protocol version");
      this.state = "IDLE";
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
    const payload = frame.payload as SessionOpenedPayload;
    const sessionId = frame.sessionId ?? payload.sessionId;
    this.log.info(`Session opened by engine: ${sessionId}`);
    await this.sendSessionOpened({
      sessionId,
      ready: true,
      channelMode: "CONTROL",
    });
  }

  private async handleSessionClose(
    frame: UnifiedFrame<"session.close">,
  ): Promise<void> {
    const payload = frame.payload as SessionClosedPayload;
    const sessionId = frame.sessionId ?? payload.sessionId;
    this.log.info(`Session close requested: ${sessionId}`);
    await this.sendSessionClosed({
      sessionId,
      closedAt: new Date().toISOString(),
      reasonCode: payload.reasonCode ?? "HOST_INITIATED",
    });
  }

  private async handleExecutionStart(
    frame: UnifiedFrame<"execution.start">,
  ): Promise<void> {
    const payload = frame.payload as ExecutionEventPayload;
    const executionId = frame.executionId ?? payload.executionId;
    this.log.info(`Execution start: ${executionId}`);
    if (this.executionHandler) {
      await this.executionHandler(frame);
    }
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
