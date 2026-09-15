// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Abstract Supervisor base shared by the Headless and Interactive runtimes.
 *
 * Ported from the lifecycle of the Python `Agent` class
 * (`myrmec/agent/agent.py`) and shaped per Â§9.6.1: a thin core that owns the
 * control socket, reconnect policy, and envelope dispatch, while everything
 * that differs between cluster and plugin lives behind a small set of
 * overridable seams (Â§9.3). Subclasses provide auth (seam 1) and may override
 * the dispatch hooks (work-initiation / presentation seams).
 *
 * This base implements REQ-A-001/002/010/011 (register, connect, reconnect,
 * token refresh). Task/turn execution is layered on top by the executor slice
 * via the overridable `on*` hooks.
 */
import {
  ReconnectingConnection,
  WebSocketConnection,
} from "../transport/index.js";
import type { Envelope, RawEnvelope } from "../protocol/envelope.js";
import { MessageType } from "../protocol/messages.js";
import {
  hostAnnounce,
  type AgentProvisions,
  type ReportedCapacity,
} from "../protocol/hostFrames.js";
import os from "node:os";
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
   * Tools + runtime this host advertises as installed (the supply side of
   * reserve-time matching). Announced on every control-socket connect.
   * Defaults to empty buckets when omitted.
   */
  provisions?: AgentProvisions;
}

export abstract class AgentSupervisor {
  protected readonly engineUrl: string;
  protected readonly log: Logger;
  protected readonly provisions: AgentProvisions;
  protected ctx: SupervisorContext;
  protected auth: AuthContext | null = null;

  private connection: WebSocketConnection | null = null;
  private reconnecting: ReconnectingConnection | null = null;
  private running = false;

  constructor(options: AgentSupervisorOptions) {
    this.engineUrl = options.engineUrl;
    this.log = options.logger ?? console;
    this.provisions = options.provisions ?? { tools: [], runtime: [] };
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
   * Start the Supervisor: authenticate, open the control socket, and run the
   * receive/reconnect loop until `stop()`. Blocks for the Supervisor's life.
   */
  async start(): Promise<void> {
    this.log.info("Starting supervisorâ€¦");
    this.running = true;

    this.auth = await this.authenticate();

    // Bring up the worker pool before the socket so a worker is ready to take
    // the first engine-pushed frame (Â§9.6.1 spawnWorkers seam).
    await this.spawnWorkers();

    this.connection = new WebSocketConnection({
      engineUrl: this.engineUrl,
      onMessage: (frame) => this.onEnvelope(frame),
      onConnect: () => this.onConnect(),
      onDisconnect: (code, reason) => this.onDisconnect(code, reason),
    });

    this.reconnecting = new ReconnectingConnection({
      connection: this.connection,
      getAccessToken: () => this.getAccessToken(),
      refreshToken: () => this.refreshTokens(),
      register: () => this.reRegister(),
    });

    await this.reconnecting.connectWithRetry();
    // From here the socket's close handler drives reconnect via onDisconnect;
    // start() resolves once connected, matching the Python run() entry.
  }

  /** Request graceful shutdown: stop reconnecting and close the socket. */
  async stop(reason = "Shutdown requested"): Promise<void> {
    this.log.info("Shutdown requested:", reason);
    this.running = false;
    this.reconnecting?.stop();
    await this.stopWorkers();
    await this.connection?.disconnect(reason);
  }

  /** True while the Supervisor is running. */
  get isRunning(): boolean {
    return this.running;
  }

  // ==================== Reconnect wiring ====================

  /** Fired once the control socket opens. Announces host capacity (Â§9.9). */
  protected async onConnect(): Promise<void> {
    this.log.info("Control socket connected");
    await this.announce();
  }

  /**
   * Advertise this host's provisions + auto-sized capacity to the engine
   * (`host.announce`). Sent on every connect so the engine always holds the
   * host's current supply (the host is the source of truth, agent-host-model
   * Â§4.3). Capacity is read from the running machine.
   */
  protected async announce(): Promise<void> {
    const reportedCapacity: ReportedCapacity = {
      cpuCount: os.cpus().length,
      totalMemoryBytes: os.totalmem(),
    };
    await this.send(hostAnnounce(this.provisions, reportedCapacity));
    this.log.debug("Sent host.announce", this.provisions, reportedCapacity);
  }

  /**
   * Fired when the socket closes. Runs the close-code reaction (refresh /
   * re-register / stop) and, if reconnection is warranted, reconnects with
   * backoff. This is the glue the Python `run()` loop performed inline.
   */
  protected async onDisconnect(code: number, reason: string): Promise<void> {
    this.log.info(`Control socket closed: code=${code} reason=${reason}`);
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

  // ==================== Envelope dispatch ====================

  /**
   * Default envelope router (mirrors Python `_handle_message`). `ping` is
   * already answered in the transport layer, so it never reaches here.
   * Subclasses/executor override the `on*` hooks below.
   */
  protected async onEnvelope(frame: RawEnvelope): Promise<void> {
    switch (frame.type) {
      case MessageType.TASK_ASSIGN:
        return this.onTaskAssign(frame);
      case MessageType.TASK_CANCEL:
        return this.onTaskCancel(frame);
      case MessageType.CONVERSATION_TURN_ASSIGN:
        return this.onConversationTurnAssign(frame);
      case MessageType.APPROVAL_DECISION:
        return this.onApprovalDecision(frame);
      // â”€â”€ Unified Inference Dispatch (Â§5) + orchestration (Â§16.3) â”€â”€
      // Frames the engine streams to a connected agent: forwarded into
      // the worker pool (the worker routes orchestration payloads itself).
      case MessageType.SESSION_OPEN:
      case MessageType.SESSION_CLOSE:
      case MessageType.INFERENCE_ASSIGN:
      case MessageType.INFERENCE_CANCEL:
      case MessageType.ORCHESTRATION_RELEASE:
      case MessageType.ORCHESTRATION_BUDGET_UPDATED:
        return this.onInferenceFrame(frame);
      default:
        this.log.warn("Unknown message type:", frame.type);
    }
  }

  /** A session/inference/orchestration frame from the engine (Â§5/Â§16.3):
   * forwarded to the worker pool. Overridable for tests. */
  protected async onInferenceFrame(frame: RawEnvelope): Promise<void> {
    this.forwardToWorker(frame);
  }

  /** Send a frame on the control socket. */
  protected send(frame: Parameters<WebSocketConnection["send"]>[0]): Promise<void> {
    if (!this.connection) {
      return Promise.reject(new Error("Supervisor not connected"));
    }
    return this.connection.send(frame);
  }

  // ==================== Worker forwarding ====================

  /**
   * Forward an inbound frame to the worker pool. The base default logs; the
   * concrete Supervisor overrides this to dispatch into its worker host
   * (Â§9.7). Conversation-socket inbound (`conversation.turn.assign`,
   * `approval.decision`) and reserve-time bind/release frames both flow here.
   */
  protected forwardToWorker(frame: RawEnvelope): void {
    this.log.debug("forwardToWorker (no worker wired):", frame.type);
  }

  /**
   * Route a frame the worker produced. Under the unified protocol (P6-T6)
   * every worker frame rides the host control socket - the per-conversation
   * socket split is gone with the legacy wire (9.3 seam 4 collapsed).
   */
  protected async routeWorkerFrame(frame: Envelope): Promise<void> {
    await this.send(frame);
  }
  // Overridable dispatch hooks â€” default to a logged no-op. The executor
  // slice fills these in. Kept as seams so work-initiation (headless vs
  // interactive) and presentation differences stay out of this core.
  protected async onTaskAssign(_frame: RawEnvelope): Promise<void> {
    this.log.debug("task.assign received (no executor wired yet)");
  }

  protected async onTaskCancel(_frame: RawEnvelope): Promise<void> {
    this.log.debug("task.cancel received (no executor wired yet)");
  }

  protected async onConversationTurnAssign(_frame: RawEnvelope): Promise<void> {
    this.log.debug("conversation.turn.assign received (no handler wired yet)");
  }

  protected async onApprovalDecision(_frame: RawEnvelope): Promise<void> {
    this.log.debug("approval.decision received (no handler wired yet)");
  }
}
