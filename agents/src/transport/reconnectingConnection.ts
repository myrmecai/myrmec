// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * WebSocket connection with automatic reconnection.
 *
 * Ported from the Python SDK (`ReconnectingConnection`). Wraps a
 * `WebSocketConnection` and adds:
 *   - exponential backoff on connect failure (REQ-A-010);
 *   - token refresh on close 4001 (TOKEN_EXPIRED);
 *   - re-registration on close 4002 (INVALID_TOKEN), and as the fallback when
 *     a refresh fails (REQ-A-002, REQ-A-011);
 *   - permanent stop on 4003 (AGENT_DEACTIVATED).
 */
import { CloseCode } from "../protocol/messages.js";
import type { WebSocketConnection } from "./connection.js";

/** Returns the current agent access token. */
export type GetAccessToken = () => Promise<string>;
/** Refreshes the token, returning a new access token. */
export type RefreshToken = () => Promise<string>;
/** Re-registers the host, returning a new access token. */
export type Register = () => Promise<string>;

export interface ReconnectingConnectionOptions {
  connection: WebSocketConnection;
  getAccessToken: GetAccessToken;
  refreshToken: RefreshToken;
  register: Register;
  /** Maximum backoff in ms (default 32 000, matching Python's 32 s). */
  maxBackoffMs?: number;
  /** Initial backoff in ms (default 1 000). */
  initialBackoffMs?: number;
  /** Sleep impl override (tests inject a fake to avoid real timers). */
  sleep?: (ms: number) => Promise<void>;
}

const defaultSleep = (ms: number): Promise<void> =>
  new Promise((resolve) => setTimeout(resolve, ms));

export class ReconnectingConnection {
  private readonly connection: WebSocketConnection;
  private readonly getAccessToken: GetAccessToken;
  private readonly refreshToken: RefreshToken;
  private readonly register: Register;
  private readonly maxBackoffMs: number;
  private readonly initialBackoffMs: number;
  private readonly sleep: (ms: number) => Promise<void>;

  private shouldReconnect = true;
  private backoffMs: number;

  constructor(options: ReconnectingConnectionOptions) {
    this.connection = options.connection;
    this.getAccessToken = options.getAccessToken;
    this.refreshToken = options.refreshToken;
    this.register = options.register;
    this.maxBackoffMs = options.maxBackoffMs ?? 32_000;
    this.initialBackoffMs = options.initialBackoffMs ?? 1_000;
    this.sleep = options.sleep ?? defaultSleep;
    this.backoffMs = this.initialBackoffMs;
  }

  /** Connect, retrying with exponential backoff until success or `stop()`. */
  async connectWithRetry(): Promise<void> {
    while (this.shouldReconnect) {
      try {
        const token = await this.getAccessToken();
        await this.connection.connect(token);
        this.backoffMs = this.initialBackoffMs; // reset on success
        return;
      } catch {
        await this.sleep(this.backoffMs);
        this.backoffMs = Math.min(this.backoffMs * 2, this.maxBackoffMs);
      }
    }
  }

  /**
   * React to a disconnect close code, performing any refresh/re-register the
   * code demands.
   *
   * @returns true if the caller should attempt to reconnect.
   */
  async handleDisconnect(code: number, _reason: string): Promise<boolean> {
    if (!this.shouldReconnect) {
      return false;
    }

    switch (code) {
      case CloseCode.NORMAL:
        return false;

      case CloseCode.TOKEN_EXPIRED:
        try {
          await this.refreshToken();
        } catch {
          // Refresh failed — fall back to a full re-register.
          await this.register();
        }
        return true;

      case CloseCode.INVALID_TOKEN:
        await this.register();
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

  /** Stop all reconnection attempts. */
  stop(): void {
    this.shouldReconnect = false;
  }
}
