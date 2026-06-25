// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Abstract Supervisor base shared by the Headless and Interactive runtimes.
 *
 * Ported from the lifecycle of the Python `Agent` class
 * (`myrmec/agent/agent.py`) and shaped per §9.6.1: a thin core that owns the
 * control socket, reconnect policy, and envelope dispatch, while everything
 * that differs between cluster and plugin lives behind a small set of
 * overridable seams (§9.3). Subclasses provide auth (seam 1) and may override
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
import { CloseCode, MessageType } from "../protocol/messages.js";
import {
  hostAnnounce,
  type AgentProvisions,
  type ReportedCapacity,
} from "../protocol/hostFrames.js";
import {
  agentBindPayloadSchema,
  agentReleasePayloadSchema,
  agentBindAck,
  agentBindNack,
} from "../protocol/agentFrames.js";
import {
  ConversationSocket,
  type ConversationConnectionLike,
} from "./ConversationSocket.js";
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
  /**
   * Conversation-socket reconnect policy (agent-concurrency §9.11). When the
   * Agent↔home-node socket drops abnormally while the home node is still
   * reachable, the Supervisor dials the *same* `homeNodeAddr` again with
   * bounded exponential backoff before ceding to the engine failover sweep.
   */
  conversationReconnect?: ConversationReconnectConfig;
}

/**
 * Bounded reconnect policy for a dropped conversation socket (§9.11 config
 * keys `myrmec.agent.conversation.reconnect.*`). The defaults — 5 attempts at
 * a 500 ms base — sum to 15 500 ms of backoff, comfortably under the engine's
 * 70 s `host-lost-threshold`, so the Agent always concludes its reconnect
 * before the engine could reclaim the worker (the named timing invariant).
 */
export interface ConversationReconnectConfig {
  /** `myrmec.agent.conversation.reconnect.max-attempts` (default 5). */
  maxAttempts?: number;
  /** `myrmec.agent.conversation.reconnect.base-backoff-ms` (default 500). */
  baseBackoffMs?: number;
}

const DEFAULT_RECONNECT_MAX_ATTEMPTS = 5;
const DEFAULT_RECONNECT_BASE_BACKOFF_MS = 500;

export abstract class AgentSupervisor {
  protected readonly engineUrl: string;
  protected readonly log: Logger;
  protected readonly provisions: AgentProvisions;
  protected ctx: SupervisorContext;
  protected auth: AuthContext | null = null;

  private connection: WebSocketConnection | null = null;
  private reconnecting: ReconnectingConnection | null = null;
  private running = false;

  /** Conversation sockets the Supervisor owns, keyed by conversationId. One
   * per bound worker (agent-concurrency §9.9). */
  private readonly conversationSockets = new Map<string, ConversationSocket>();

  /** The `homeNodeAddr` each conversation socket was dialed against, kept so
   * an abnormal drop can be re-dialed against the *same* node (§9.11). Its
   * presence also marks a conversation as one the Supervisor still owns: it is
   * cleared on `agent.release` and on reconnect exhaustion, which is how those
   * paths stop the reconnect loop. */
  private readonly conversationHomeAddr = new Map<string, string>();

  /** Bounded conversation-socket reconnect policy (§9.11). */
  private readonly reconnectMaxAttempts: number;
  private readonly reconnectBaseBackoffMs: number;

  constructor(options: AgentSupervisorOptions) {
    this.engineUrl = options.engineUrl;
    this.log = options.logger ?? console;
    this.provisions = options.provisions ?? { tools: [], runtime: [] };
    this.reconnectMaxAttempts =
      options.conversationReconnect?.maxAttempts ??
      DEFAULT_RECONNECT_MAX_ATTEMPTS;
    this.reconnectBaseBackoffMs =
      options.conversationReconnect?.baseBackoffMs ??
      DEFAULT_RECONNECT_BASE_BACKOFF_MS;
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
    this.log.info("Starting supervisor…");
    this.running = true;

    this.auth = await this.authenticate();

    // Bring up the worker pool before the socket so a worker is ready to take
    // the first engine-pushed frame (§9.6.1 spawnWorkers seam).
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
    await this.closeAllConversationSockets();
    await this.stopWorkers();
    await this.connection?.disconnect(reason);
  }

  /** True while the Supervisor is running. */
  get isRunning(): boolean {
    return this.running;
  }

  // ==================== Reconnect wiring ====================

  /** Fired once the control socket opens. Announces host capacity (§9.9). */
  protected async onConnect(): Promise<void> {
    this.log.info("Control socket connected");
    await this.announce();
  }

  /**
   * Advertise this host's provisions + auto-sized capacity to the engine
   * (`host.announce`). Sent on every connect so the engine always holds the
   * host's current supply (the host is the source of truth, agent-host-model
   * §4.3). Capacity is read from the running machine.
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
      case MessageType.AGENT_BIND:
        return this.onAgentBind(frame);
      case MessageType.AGENT_RELEASE:
        return this.onAgentRelease(frame);
      default:
        this.log.warn("Unknown message type:", frame.type);
    }
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
   * (§9.7). Conversation-socket inbound (`conversation.turn.assign`,
   * `approval.decision`) and reserve-time bind/release frames both flow here.
   */
  protected forwardToWorker(frame: RawEnvelope): void {
    this.log.debug("forwardToWorker (no worker wired):", frame.type);
  }

  /** Frame types the worker emits that ride the conversation socket rather
   * than the control socket. */
  private static readonly CONVERSATION_OUTBOUND: ReadonlySet<string> = new Set([
    MessageType.MESSAGE_DELTA,
    MessageType.MESSAGE_COMPLETE,
    MessageType.APPROVAL_REQUEST,
    MessageType.TASK_CANCELLED,
  ]);

  /**
   * Route a frame the worker produced. Conversation-keyed output
   * (message.delta/complete, approval.request, task.cancelled) rides the
   * matching conversation socket; everything else (task/tool/log frames) rides
   * the control socket. The worker is byte-identical and unaware of this split
   * (§9.3 seam 4).
   */
  protected async routeWorkerFrame(frame: Envelope): Promise<void> {
    if (AgentSupervisor.CONVERSATION_OUTBOUND.has(frame.type)) {
      const conversationId = (
        frame.payload as { conversationId?: string } | undefined
      )?.conversationId;
      const socket = conversationId
        ? this.conversationSockets.get(conversationId)
        : undefined;
      if (socket) {
        await socket.send(frame);
        return;
      }
      this.log.warn(
        "No conversation socket for frame; falling back to control:",
        frame.type,
        conversationId,
      );
    }
    await this.send(frame);
  }

  // ==================== Conversation socket (reserve-time binding) ====================

  /**
   * Create the underlying connection for a conversation socket dialing
   * `homeNodeAddr`. The default opens a real {@link WebSocketConnection} to the
   * named pod's `/api/v1/agent/conversation` endpoint; tests override this to
   * inject a fake. `onMessage` carries inbound conversation-socket frames to
   * the worker; `onDisconnect` lets the Supervisor reap a dropped socket.
   */
  protected createConversationConnection(opts: {
    homeNodeAddr: string;
    onMessage: (frame: RawEnvelope) => void | Promise<void>;
    onDisconnect: (code: number, reason: string) => void | Promise<void>;
  }): ConversationConnectionLike {
    return new WebSocketConnection({
      engineUrl: opts.homeNodeAddr,
      path: "/api/v1/agent/conversation",
      onMessage: opts.onMessage,
      onDisconnect: opts.onDisconnect,
    });
  }

  /**
   * Open the dedicated conversation socket for a just-bound conversation:
   * dial the home node and send `conversation.attach`. When `homeNodeAddr` is
   * absent (single-node deployment) the Supervisor falls back to its own
   * control-socket engine URL.
   */
  protected async openConversationSocket(
    conversationId: string,
    homeNodeAddr: string | null,
  ): Promise<void> {
    const agentId = this.ctx.agentId;
    if (!agentId) {
      this.log.error("Cannot open conversation socket before registration");
      return;
    }
    if (this.conversationSockets.has(conversationId)) {
      this.log.warn("Conversation socket already open; replacing:", conversationId);
      await this.closeConversationSocket(conversationId);
    }

    // Remember the node we dialed so an abnormal drop re-homes to the *same*
    // address (§9.11); its presence also marks the conversation as owned.
    const resolvedAddr = homeNodeAddr ?? this.engineUrl;
    this.conversationHomeAddr.set(conversationId, resolvedAddr);

    try {
      await this.dialConversationSocket(conversationId, resolvedAddr);
      this.log.info("Conversation socket bound:", conversationId);
      // Tell the engine the bind landed and the worker is dialing — it
      // advances the reserved worker to CONNECTING (agent-concurrency §9.5).
      // Best-effort: the control socket may have dropped while we were
      // dialing the conversation socket; a rejected send must not crash the
      // process (the reconnect loop will re-establish the control socket).
      try {
        await this.send(agentBindAck({ conversationId }));
      } catch (sendErr) {
        this.log.warn(
          "Control socket closed before agent.bind.ack could be sent for",
          conversationId,
          sendErr instanceof Error ? sendErr.message : String(sendErr),
        );
      }
    } catch (err) {
      this.log.error("Failed to open conversation socket:", conversationId, err);
      this.conversationHomeAddr.delete(conversationId);
      // Tell the engine we cannot serve this bind so it releases the worker
      // back to IDLE for re-dispatch (agent-concurrency §9.5).
      try {
        await this.send(
          agentBindNack({
            conversationId,
            reason: err instanceof Error ? err.message : String(err),
          }),
        );
      } catch (sendErr) {
        this.log.warn(
          "Control socket closed before agent.bind.nack could be sent for",
          conversationId,
          sendErr instanceof Error ? sendErr.message : String(sendErr),
        );
      }
    }
  }

  /**
   * Create + open one conversation-socket connection against `homeNodeAddr`:
   * dial it and send the (idempotent) `conversation.attach`. Shared by the
   * initial bind and the §9.11 reconnect path; throws if the dial or attach
   * fails so each caller can react (initial → `agent.bind.nack`; reconnect →
   * retry/exhaust). The fresh connection re-arms `onConversationSocketClosed`
   * so a later drop re-enters the same recovery path.
   */
  private async dialConversationSocket(
    conversationId: string,
    homeNodeAddr: string,
  ): Promise<ConversationSocket> {
    const agentId = this.ctx.agentId;
    if (!agentId) {
      throw new Error("Cannot dial conversation socket before registration");
    }
    const connection = this.createConversationConnection({
      homeNodeAddr,
      onMessage: (frame) => this.forwardToWorker(frame),
      onDisconnect: (code, reason) =>
        this.onConversationSocketClosed(conversationId, code, reason),
    });
    const socket = new ConversationSocket({
      agentId,
      conversationId,
      connection,
      logger: this.log,
    });
    this.conversationSockets.set(conversationId, socket);
    try {
      const token = await this.getAccessToken();
      await socket.open(token);
    } catch (err) {
      this.conversationSockets.delete(conversationId);
      throw err;
    }
    return socket;
  }

  /** Close and forget a conversation socket (on `agent.release`). */
  protected async closeConversationSocket(conversationId: string): Promise<void> {
    // Drop the home-addr first so a NORMAL close handler (and any in-flight
    // reconnect loop) sees the conversation as no longer owned and stands down.
    this.conversationHomeAddr.delete(conversationId);
    const socket = this.conversationSockets.get(conversationId);
    if (!socket) {
      return;
    }
    this.conversationSockets.delete(conversationId);
    try {
      await socket.close();
    } catch (err) {
      this.log.warn("Error closing conversation socket:", conversationId, err);
    }
  }

  private async closeAllConversationSockets(): Promise<void> {
    const ids = [...this.conversationSockets.keys()];
    await Promise.all(ids.map((id) => this.closeConversationSocket(id)));
  }

  /**
   * Fired when a conversation socket drops (transport close). Implements the
   * §9.11 reconnect-then-rehome policy for the home-node-**UP** case: a
   * **clean** close (engine drain / `task.complete`, code `NORMAL`) or a close
   * on a conversation the Supervisor no longer owns (already released) is just
   * forgotten; an **abnormal** drop on a still-owned conversation triggers a
   * bounded reconnect to the *same* `homeNodeAddr`. If the node is genuinely
   * `DOWN` the dials fail, the loop exhausts, and recovery cedes to the engine
   * failover sweep — so the two triggers never fire for the same worker.
   */
  protected onConversationSocketClosed(
    conversationId: string,
    code: number,
    reason: string,
  ): void | Promise<void> {
    this.conversationSockets.delete(conversationId);
    const homeNodeAddr = this.conversationHomeAddr.get(conversationId);
    const cleanClose = code === CloseCode.NORMAL;
    if (homeNodeAddr === undefined || cleanClose) {
      // Released by us, or a clean engine-side close — do not re-home.
      this.conversationHomeAddr.delete(conversationId);
      this.log.info(
        `Conversation socket closed: conv=${conversationId} code=${code} reason=${reason}`,
      );
      return;
    }
    this.log.warn(
      `Conversation socket dropped abnormally (code=${code} reason=${reason}); ` +
        `reconnecting to ${homeNodeAddr}: conv=${conversationId}`,
    );
    return this.reconnectConversationSocket(conversationId, homeNodeAddr);
  }

  /**
   * Bounded reconnect of a dropped conversation socket to the *same* home node
   * (§9.11, home-node-UP case). Re-dials with exponential backoff up to
   * `reconnectMaxAttempts`; each successful dial re-sends `conversation.attach`,
   * which the engine treats idempotently (single `BOUND`, recorded as
   * `CONVERSATION_REATTACHED`). On exhaustion it stops dialing and clears the
   * home-addr, ceding recovery to the engine failover sweep / `HOST_LOST`
   * reaper. The total backoff window stays under the engine `host-lost-threshold`
   * so the Agent always finishes before the worker could be reclaimed.
   */
  private async reconnectConversationSocket(
    conversationId: string,
    homeNodeAddr: string,
  ): Promise<void> {
    for (let attempt = 1; attempt <= this.reconnectMaxAttempts; attempt++) {
      const backoffMs = this.reconnectBaseBackoffMs * 2 ** (attempt - 1);
      await this.delay(backoffMs);
      // Released (or replaced) while we were backing off → stop dialing.
      if (this.conversationHomeAddr.get(conversationId) !== homeNodeAddr) {
        return;
      }
      try {
        await this.dialConversationSocket(conversationId, homeNodeAddr);
        this.log.info(
          `Conversation socket re-attached to ${homeNodeAddr} ` +
            `(attempt ${attempt}/${this.reconnectMaxAttempts}): ${conversationId}`,
        );
        return; // attach re-sent → engine records CONVERSATION_REATTACHED
      } catch (err) {
        this.log.warn(
          `Conversation reconnect attempt ${attempt}/${this.reconnectMaxAttempts} ` +
            `to ${homeNodeAddr} failed: ${conversationId}`,
          err instanceof Error ? err.message : err,
        );
      }
    }
    // Exhausted: the home node is unreachable. Stop dialing it; the engine
    // failover sweep (home node DOWN) or the HOST_LOST reaper now owns recovery.
    this.conversationHomeAddr.delete(conversationId);
    this.log.warn(
      `Conversation reconnect exhausted after ${this.reconnectMaxAttempts} ` +
        `attempts; ceding to engine failover: ${conversationId}`,
    );
  }

  /** Backoff sleep between conversation-socket reconnect attempts. Overridable
   * so tests can drive the loop without real timers. */
  protected delay(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  // Overridable dispatch hooks — default to a logged no-op. The executor
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

  /**
   * `agent.bind` — the engine reserved this worker for a conversation and named
   * the home node to dial. Open the dedicated conversation socket and hand the
   * binding to the worker (byte-identical recording).
   */
  protected async onAgentBind(frame: RawEnvelope): Promise<void> {
    const parsed = agentBindPayloadSchema.safeParse(frame.payload);
    if (!parsed.success) {
      this.log.warn("Invalid agent.bind payload:", parsed.error.issues);
      return;
    }
    this.forwardToWorker(frame);
    await this.openConversationSocket(
      parsed.data.conversationId,
      parsed.data.homeNodeAddr ?? null,
    );
  }

  /**
   * `agent.release` — the engine tore the binding down. Close the conversation
   * socket and let the worker drop its conversation state.
   */
  protected async onAgentRelease(frame: RawEnvelope): Promise<void> {
    const parsed = agentReleasePayloadSchema.safeParse(frame.payload);
    if (!parsed.success) {
      this.log.warn("Invalid agent.release payload:", parsed.error.issues);
      return;
    }
    this.forwardToWorker(frame);
    await this.closeConversationSocket(parsed.data.conversationId);
  }
}
