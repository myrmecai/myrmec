// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Abstract Supervisor base shared by the Headless and Interactive runtimes —
 * the PRODUCTION COMPOSITION ROOT for the unified host-control wire (§6–§11).
 *
 * The legacy transport stack (`WebSocketConnection` + `ReconnectingConnection`
 * + the `/api/v1/agent/ws` wire) is deleted: the supervisor now owns a
 * {@link HostControlClient} that drives the host-control socket end to end —
 * connect + HOST_JWT handshake, host.open/opened, heartbeat, session offers
 * (auto-accept), session.open dispatch (§7.5 channel binding), execution
 * routing, and §13 retention/recovery. The supervisor's job shrinks to:
 *
 *   1. adapt the subclass's auth seam onto the client's `HostTokenProvider`,
 *   2. report capacity/pool in `host.open` (replaces the legacy
 *      `host.announce` — capacity now rides host.open + heartbeat),
 *   3. route inbound commands (execution.start/cancel) to the overridable
 *      `on*` hooks the executor slice fills in,
 *   4. route worker-produced frames out through the client's typed senders,
 *   5. wire the SessionRegistry seams (sessionLifecycle / retention / PSK)
 *      into the client when a registry is present.
 *
 * This base implements REQ-A-001/002/010/011 (register, connect, reconnect,
 * token refresh) — reconnect + refresh/re-register are the client's internal
 * path, driven by the same close-code reaction the old supervisor loop ran.
 */
import {
  HostControlClient,
  type ExecutionCancelHandler,
  type ExecutionHandler,
  type HostControlConnection,
  type HostRetentionLifecycle,
  type HostSessionLifecycle,
  type HostTokenProvider,
} from "./HostControlClient.js";
import type { Envelope, RawEnvelope } from "../protocol/envelope.js";
import type {
  ExecutionAcceptPayload,
  ExecutionApprovalRequestedPayload,
  ExecutionCancelPayload,
  ExecutionCancelledPayload,
  ExecutionCompletePayload,
  ExecutionDeltaPayload,
  ExecutionEventPayload,
  ExecutionFailedPayload,
  ExecutionPausedPayload,
  ExecutionRejectPayload,
  ProtocolErrorPayload,
  SessionOpenPayload,
} from "../protocol/unifiedFrames.js";
import type {
  AuthContext,
  Logger,
  SupervisorContext,
  SupervisorRole,
  Task,
  WorkspaceHandle,
} from "../models/index.js";

export interface AgentSupervisorOptions {
  engineUrl: string;
  role: SupervisorRole;
  logger?: Logger;
  /**
   * Worker-pool size reported in `host.open` + heartbeat. The subclass sets
   * it from its spawnWorkers configuration; default 1 (V1 pool = 1).
   */
  poolSize?: number;
  /**
   * Extra host capabilities (tools/runtime catalog) reported in `host.open`
   * — the supply side of reserve-time matching (replaces the legacy
   * `host.announce` provisions).
   */
  capabilities?: Record<string, unknown>;
  /**
   * CPU/RAM capacity the pool was auto-sized from, reported in `host.open`
   * + heartbeat (replaces the legacy `host.announce` reportedCapacity).
   */
  reportedCapacity?: Record<string, unknown>;
  /**
   * Local-owner model (§3.7/§4.1): the id of the logged-in user the plugin
   * supplies — forwarded into the client's host.open payload (LOCAL hosts
   * stamp instance ownership from it). Headless/MANAGED leaves it unset.
   */
  ownerUserId?: string;
  /**
   * Connection override (tests inject a fake) — forwarded to the client.
   */
  connection?: HostControlConnection;
  /**
   * Override the host-control socket path. Defaults to the client's own
   * unified path (`/api/v1/agent/host/ws`).
   */
  path?: string;
}

export abstract class AgentSupervisor {
  protected readonly engineUrl: string;
  protected readonly log: Logger;
  protected readonly poolSize: number;
  protected readonly capabilities: Record<string, unknown>;
  protected readonly reportedCapacity: Record<string, unknown>;
  /** §3.7: the plugin's logged-in user id (LOCAL hosts), else undefined. */
  protected readonly ownerUserId: string | undefined;
  protected ctx: SupervisorContext;
  protected auth: AuthContext | null = null;

  /**
   * §7.5 (A1) + §13 (A2): the session-lifecycle collaborator (the
   * SessionRegistry). When set before {@link start}, the client binds
   * dedicated channels for channel-bearing session.opens and retains
   * sessions across control-socket drops (host.resume/host.reconcile
   * instead of teardown).
   */
  protected sessionRegistry?: HostSessionLifecycle & HostRetentionLifecycle;

  /** The host-control path override (tests/consumers may pin it). */
  private path: string | undefined;
  /** Connection override captured from options (tests inject a fake). */
  private optionsConnection: HostControlConnection | undefined;
  private client: HostControlClient | null = null;
  private running = false;

  constructor(options: AgentSupervisorOptions) {
    this.engineUrl = options.engineUrl;
    this.log = options.logger ?? console;
    this.poolSize = options.poolSize ?? 1;
    this.capabilities = options.capabilities ?? {};
    this.reportedCapacity = options.reportedCapacity ?? {};
    this.ownerUserId = options.ownerUserId;
    this.path = options.path;
    this.optionsConnection = options.connection;
    this.ctx = {
      hostId: "",
      role: options.role,
      controlEndpoint: options.engineUrl,
    };
  }

  // ==================== Seam 1: authentication ====================

  /**
   * Establish the initial auth state (sets `this.auth`). Headless does a
   * registration-key bootstrap; Interactive does loopback PKCE +
   * self-activation. Must return the AuthContext it also stored on `this.auth`.
   */
  protected abstract authenticate(): Promise<AuthContext>;

  /** Current agent access token used to (re)open the control socket. */
  protected abstract getAccessToken(): Promise<string>;

  /** Refresh the agent token (close 4001); returns the new access token. */
  protected abstract refreshTokens(): Promise<string>;

  /** Re-register (close 4002, or refresh fallback); returns new access token. */
  protected abstract reRegister(): Promise<string>;

  // ==================== Seam 3: workspace ====================

  /** Resolve the workspace a worker runs against (clone vs open folder). */
  protected abstract resolveWorkspace(task: Task): Promise<WorkspaceHandle>;

  // ==================== Worker pool ====================

  /**
   * Bring up the Agent worker pool. The base default is a no-op; subclasses
   * spawn one (interactive) or N (headless, auto-sized) worker threads. Called
   * once during {@link start} after auth, before the control socket opens.
   */
  protected async spawnWorkers(): Promise<void> {
    // No workers by default; subclasses override.
  }

  /** Tear the worker pool down. Called during {@link stop} before disconnect. */
  protected async stopWorkers(): Promise<void> {
    // No workers by default; subclasses override.
  }

  // ==================== Lifecycle ====================

  /**
   * Build the unified host-control client from this supervisor's state. The
   * token provider adapts the subclass's auth seam onto the client's
   * `HostTokenProvider` contract; the registry seams (sessionLifecycle /
   * retention / PSK) are wired when the subclass set a registry.
   */
  private buildClient(): HostControlClient {
    const tokenProvider: HostTokenProvider = {
      getAccessToken: () => this.getAccessToken(),
      refreshTokens: () => this.refreshTokens(),
      reRegister: () => this.reRegister(),
    };
    const client = new HostControlClient({
      engineUrl: this.engineUrl,
      tokenProvider,
      logger: this.log,
      poolSize: this.poolSize,
      capabilities: this.capabilities,
      reportedCapacity: this.reportedCapacity,
      // §3.7 local-owner model: the plugin's logged-in user id rides
      // host.open; headless supervisors leave it undefined (field omitted).
      ...(this.ownerUserId !== undefined ? { ownerUserId: this.ownerUserId } : {}),
      // Runtime version constant: the SDK version (§6 host.open contract).
      runtimeVersion: "0.1.0",
      ...(this.path ? { path: this.path } : {}),
      ...(this.optionsConnection ? { connection: this.optionsConnection } : {}),
      // §6 (design 2026-09-16-credential-envelope-delivery.md): hand the
      // delivered run PSK to the subclass's registry seam when present.
      onPsk: (psk) => this.onPsk(psk),
      // §7.5 (A1) + §13 (A2): the registry implements both collaborator
      // surfaces; when absent the client stays control-only and pre-A2.
      ...(this.sessionRegistry
        ? { sessionLifecycle: this.sessionRegistry }
        : {}),
      ...(this.sessionRegistry ? { retention: this.sessionRegistry } : {}),
    });
    client.onExecutionStart((frame) => this.onExecutionStart(frame));
    client.onExecutionCancel((frame) => this.onExecutionCancel(frame));
    // Mirror session opens/closes to the subclass's forwarding hooks (the
    // worker's registry opens/closes the model + tools alongside the
    // transport-side bookkeeping).
    client.onSessionOpen(async (frame) => {
      this.onSessionOpen(frame.payload as SessionOpenPayload);
    });
    client.onSessionClose(async (frame) => {
      const payload = frame.payload as { sessionId?: string };
      if (payload.sessionId) {
        this.onSessionClose({ sessionId: payload.sessionId });
      }
    });
    return client;
  }

  /**
   * Start the Supervisor: authenticate, bring up the worker pool, open the
   * host-control socket (host.open → host.opened → heartbeat) and run until
   * `stop()`. Blocks until the socket is connected — the client owns
   * reconnection from there (the old `onDisconnect` retry loop is the
   * client's internal path now).
   */
  async start(): Promise<void> {
    this.log.info("Starting supervisor…");
    this.running = true;

    this.auth = await this.authenticate();

    // Bring up the worker pool before the socket so a worker is ready to take
    // the first engine-pushed frame (§9.6.1 spawnWorkers seam).
    await this.spawnWorkers();

    this.client = this.buildClient();
    await this.client.start();
  }

  /** Request graceful shutdown: stop the client (heartbeats, channels, socket). */
  async stop(reason = "Shutdown requested"): Promise<void> {
    this.log.info("Shutdown requested:", reason);
    this.running = false;
    await this.stopWorkers();
    await this.client?.stop(reason);
  }

  /** True while the Supervisor is running. */
  get isRunning(): boolean {
    return this.running;
  }

  // ==================== Inbound routing (unified wire) ====================

  /**
   * Unified `execution.start` — the ONE work-initiation frame on the
   * host-control socket (conversation + orchestration alike; the frame
   * discriminates by payload shape). Overridable for tests.
   */
  protected async onExecutionStart(
    _frame: Parameters<ExecutionHandler>[0],
  ): Promise<void> {
    this.log.debug("execution.start received (no executor wired yet)");
  }

  /**
   * Unified `execution.cancel` — cancellation for an in-flight execution.
   * Overridable for tests.
   */
  protected async onExecutionCancel(
    _frame: Parameters<ExecutionCancelHandler>[0],
  ): Promise<void> {
    this.log.debug("execution.cancel received (no handler wired yet)");
  }

  /**
   * A `session.open` the client dispatched (after channel bind + opened
   * reply). The base default is a logged no-op; subclasses forward to the
   * worker registry.
   */
  protected onSessionOpen(_payload: SessionOpenPayload): void {
    this.log.debug("session.open received (no worker wired yet)");
  }

  /** A `session.close` the client dispatched. Base default: no-op. */
  protected onSessionClose(_payload: { sessionId: string }): void {
    this.log.debug("session.close received (no worker wired yet)");
  }

  // ==================== PSK delivery (§6, credential envelope design) ========

  /**
   * The run PSK decoded from `host.opened` (32 bytes, process memory only —
   * never logged, never persisted). The base default forwards to the wired
   * session registry's `setPsk` when one is present; otherwise no-op.
   * Subclasses may override for their own vault plumbing.
   */
  protected onPsk(psk: Uint8Array): void {
    const registry = this.sessionRegistry as unknown as
      | { setPsk?: (psk: Uint8Array) => void }
      | undefined;
    registry?.setPsk?.(psk);
  }

  // ==================== Worker forwarding ====================

  /**
   * Forward an inbound frame to the worker pool. The base default logs; the
   * concrete Supervisor overrides this to dispatch into its worker host
   * (§9.7). Conversation-socket inbound and reserve-time frames are gone
   * with the legacy wire — everything unified flows here.
   */
  protected forwardToWorker(frame: RawEnvelope): void {
    this.log.debug("forwardToWorker (no worker wired):", frame.type);
  }

  /** Convert a unified frame to a worker envelope and hand it to the
   * overridable forwarding seam. */
  protected forwardToWorkerEnvelope(frame: Envelope): void {
    this.forwardToWorker(frame as RawEnvelope);
  }

  /**
   * Reconcile the session identity on a re-wrapped execution frame.
   *
   * Engine convention (§8.1): the envelope-level `sessionId` on
   * execution.start/cancel is the allocator-owned session id (stamped by
   * ExecutionCommandSender from the session row), while the payload's own
   * `sessionId` field carries the CONVERSATION id for conversation turns.
   * The worker's SessionRegistry, however, keys sessions by the id from
   * session.open (the session id) — so a forwarded frame must carry the
   * transport-level id or the executor's registry lookup misses and the
   * turn dead-ends (SESSION_NOT_OPEN → a terminal without an accept →
   * engine INVALID_STATE).
   */
  protected static reconcileTransportSessionId(
    payload: Record<string, unknown>,
    frameSessionId: string | null | undefined,
  ): Record<string, unknown> {
    if (frameSessionId && payload.sessionId !== frameSessionId) {
      return { ...payload, sessionId: frameSessionId };
    }
    return payload;
  }

  /**
   * Route a frame the worker produced onto the unified wire. Under the
   * unified protocol (P6-T6) every worker frame rides the host control
   * socket — the per-conversation socket split is gone with the legacy wire
   * (§9.3 seam 4 collapsed).
   */
  protected async routeWorkerFrame(frame: Envelope): Promise<void> {
    await this.post(frame);
  }

  /**
   * Post a worker-produced frame to the engine. Maps the legacy Envelope
   * shape (the worker's only sink) onto the client's typed senders. Warns
   * (and drops) when the client is not connected (shutdown race).
   */
  protected async post(frame: Envelope): Promise<void> {
    const client = this.client;
    if (!client) {
      this.log.warn("post() before start — dropping frame:", frame.type);
      return;
    }
    const payload = frame.payload as Record<string, unknown>;
    switch (frame.type) {
      case "execution.accept":
        return client.sendExecutionAccept(
          payload as unknown as ExecutionAcceptPayload,
        );
      case "execution.reject":
        return client.sendExecutionReject(
          payload as unknown as ExecutionRejectPayload,
        );
      case "execution.delta":
        return client.sendExecutionDelta(
          payload as unknown as ExecutionDeltaPayload,
        );
      case "execution.event":
        return client.sendExecutionEvent(
          payload as unknown as ExecutionEventPayload,
        );
      case "execution.complete":
        return client.sendExecutionComplete(
          payload as unknown as ExecutionCompletePayload,
        );
      case "execution.failed":
        return client.sendExecutionFailed(
          payload as unknown as ExecutionFailedPayload,
        );
      case "execution.paused":
        return client.sendExecutionPaused(
          payload as unknown as ExecutionPausedPayload,
        );
      case "execution.cancelled":
        return client.sendExecutionCancelled(
          payload as unknown as ExecutionCancelledPayload,
        );
      case "execution.cancel":
        return client.sendExecutionCancel(
          payload as unknown as ExecutionCancelPayload,
        );
      case "execution.approval.requested":
        return client.sendExecutionApprovalRequested(
          payload as unknown as ExecutionApprovalRequestedPayload,
        );
      case "protocol.error":
        return client.sendProtocolError(
          payload as unknown as ProtocolErrorPayload,
        );
      default:
        this.log.warn("Unknown worker frame type — dropped:", frame.type);
    }
  }
}
