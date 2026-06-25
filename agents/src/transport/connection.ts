// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * WebSocket connection to the Engine.
 *
 * Ported from the Python SDK (`myrmec/agent/connection.py` →
 * `WebSocketConnection`). Handles: connecting with a JWT, a receive loop that
 * dispatches frames, sending frames, and protocol-level ping/pong keep-alive.
 * Automatic reconnection lives in `ReconnectingConnection` (REQ-A-010).
 *
 * The library-level ping is disabled — we answer the Engine's protocol `ping`
 * with a protocol `pong`, matching the Python `ping_interval=None` choice.
 */
import { WebSocket, type RawData } from "ws";
import { CloseCode, MessageType } from "../protocol/messages.js";
import {
  decodeEnvelope,
  encodeEnvelope,
  makeEnvelope,
  type Envelope,
  type RawEnvelope,
} from "../protocol/envelope.js";

/** Callback for a decoded inbound frame (anything except `ping`). */
export type OnMessage = (frame: RawEnvelope) => void | Promise<void>;
/** Callback fired once the socket is open. */
export type OnConnect = () => void | Promise<void>;
/** Callback fired when the socket closes (code, reason). */
export type OnDisconnect = (code: number, reason: string) => void | Promise<void>;

export interface WebSocketConnectionOptions {
  /** Base Engine URL (http(s):// is converted to ws(s)://). */
  engineUrl: string;
  /** WebSocket path to open. Defaults to the control socket
   * (`/api/v1/agent/ws`); the conversation socket passes
   * `/api/v1/agent/conversation`. */
  path?: string;
  onMessage: OnMessage;
  onConnect?: OnConnect;
  onDisconnect?: OnDisconnect;
}

export class WebSocketConnection {
  private readonly engineUrl: string;
  private readonly path: string;
  private readonly onMessage: OnMessage;
  private readonly onConnect?: OnConnect;
  private readonly onDisconnect?: OnDisconnect;

  private ws: WebSocket | null = null;
  private running = false;

  constructor(options: WebSocketConnectionOptions) {
    this.engineUrl = options.engineUrl;
    this.path = options.path ?? "/api/v1/agent/ws";
    this.onMessage = options.onMessage;
    this.onConnect = options.onConnect;
    this.onDisconnect = options.onDisconnect;
  }

  /** True while the socket is open. */
  get isConnected(): boolean {
    return this.ws !== null && this.ws.readyState === WebSocket.OPEN;
  }

  /**
   * Connect to the Engine. Resolves once the socket is OPEN (after firing
   * `onConnect`); rejects if the socket errors before opening.
   */
  connect(accessToken: string): Promise<void> {
    const url = this.getWsUrl(accessToken);

    return new Promise<void>((resolve, reject) => {
      // Disable ws-level heartbeat; we handle ping/pong at the protocol level.
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

  /** Wire up the steady-state receive/close handlers after a successful open. */
  private attachHandlers(ws: WebSocket): void {
    ws.on("message", (raw: RawData) => {
      void this.handleRaw(raw);
    });

    ws.on("close", (code: number, reasonBuf: Buffer) => {
      this.running = false;
      const reason = reasonBuf.toString();
      void this.onDisconnect?.(code, reason);
    });

    // Post-open transport errors surface as a disconnect, not a throw.
    ws.on("error", (err: Error) => {
      if (this.running) {
        this.running = false;
        void this.onDisconnect?.(CloseCode.GOING_AWAY, err.message);
      }
    });
  }

  private async handleRaw(raw: RawData): Promise<void> {
    if (!this.running) {
      return;
    }
    let frame: RawEnvelope;
    try {
      frame = decodeEnvelope(raw.toString());
    } catch {
      // Invalid/malformed frame — drop it, never let it reach a handler.
      return;
    }

    if (frame.type === MessageType.PING) {
      await this.handlePing();
      return;
    }
    await this.onMessage(frame);
  }

  private async handlePing(): Promise<void> {
    try {
      await this.send(makeEnvelope(MessageType.PONG, {}));
    } catch {
      // A failed pong means the socket is already gone; the close handler
      // will drive reconnect.
    }
  }

  /** Send a frame. Throws if the socket is not open. */
  send(frame: Envelope): Promise<void> {
    const ws = this.ws;
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      return Promise.reject(new Error("WebSocket not connected"));
    }
    return new Promise<void>((resolve, reject) => {
      ws.send(encodeEnvelope(frame), (err) => (err ? reject(err) : resolve()));
    });
  }

  /** Gracefully disconnect: best-effort DISCONNECT frame, then close NORMAL. */
  async disconnect(reason = "Agent shutdown"): Promise<void> {
    this.running = false;
    const ws = this.ws;
    if (ws && ws.readyState === WebSocket.OPEN) {
      try {
        await this.send(makeEnvelope(MessageType.DISCONNECT, { reason }));
      } catch {
        // ignore — we are closing anyway
      }
      try {
        ws.close(CloseCode.NORMAL, reason);
      } catch {
        // ignore
      }
    }
    this.ws = null;
  }

  /** Convert the http(s) Engine URL to a ws(s) socket URL with token. */
  private getWsUrl(accessToken: string): string {
    const base = this.engineUrl.replace(/\/+$/, "");
    let wsBase: string;
    if (base.startsWith("https://")) {
      wsBase = "wss://" + base.slice("https://".length);
    } else if (base.startsWith("http://")) {
      wsBase = "ws://" + base.slice("http://".length);
    } else {
      wsBase = "ws://" + base;
    }
    return `${wsBase}${this.path}?token=${encodeURIComponent(accessToken)}`;
  }
}
