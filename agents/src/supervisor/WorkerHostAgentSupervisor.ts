// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * The worker-host composition supervisor: EVERY concrete supervisor that owns
 * an Agent worker thread shares this intermediate class - it owns ALL
 * worker-host composition behavior once (spawn/stop, engine-ack +
 * session-lifecycle bridge wiring, and the inbound -> worker forwarding
 * overrides), leaving the subclasses with only creational parameters
 * (the {@link AgentWorkerConfig} thunk) and callbacks (worker frame /
 * worker exit observers).
 *
 * Formerly the body of `HeadlessAgentSupervisor` (and, before it, duplicated
 * across the headless supervisor and the plugin's LocalAgentSupervisor -
 * evidence of the drift this class removes); the headless supervisor now
 * slims down to its auth seam + workspace gap on top of this class.
 *
 * Inbound forwarding behavior (verbatim from the headless supervisor):
 * every unified command frame is re-wrapped as a legacy-shaped worker
 * Envelope and dispatched into the spawned worker host, and the frames the
 * worker emits are routed onto the unified wire through the base's typed
 * `post()` (seam 4 = engine only), plus the documented headless PSK fix:
 * `onPsk` hands the run PSK across the worker boundary (the base default
 * only reaches a registry's `setPsk`, which the conservative bridge never
 * implements - headless previously dropped the PSK here).
 */
import { AgentSupervisor, type AgentSupervisorOptions } from "./AgentSupervisor.js";
import {
  spawnAgentWorkerHost,
  type AgentWorkerHost,
} from "../worker/AgentWorkerHost.js";
import type { AgentWorkerConfig } from "../worker/agentWorkerProtocol.js";
import {
  makeEnvelope,
  type Envelope,
  type RawEnvelope,
} from "../protocol/envelope.js";
import type {
  ExecutionCancelHandler,
  ExecutionHandler,
  HostRetentionLifecycle,
} from "./HostControlClient.js";
import type {
  ExecutionCancelPayload,
  ExecutionStartPayload,
  OrchestrationExecutionStartPayload,
  ParsedUnifiedFrame,
  SessionOpenPayload,
} from "../protocol/unifiedFrames.js";

/**
 * Options for a worker-host composition. The `config` may be a plain value
 * or a thunk - a thunk is resolved at spawn time so it can capture state
 * resolved after construction (e.g. the fresh registration access token).
 */
export interface WorkerHostSupervisorOptions extends AgentSupervisorOptions {
  worker: {
    /** The worker creational config (value, or thunk resolved at spawn). */
    config: AgentWorkerConfig | (() => AgentWorkerConfig);
    /**
     * Observe an Agent-produced frame as it crosses the supervisor on its
     * way to the engine (invoked BEFORE the engine post). The interactive
     * composition uses this to mirror conversation-socket frames to the
     * editor; optional.
     */
    onWorkerFrame?: (frame: Envelope) => void;
    /**
     * Observe the worker's exit. When absent the composition logs a warn
     * (the headless default); restart policy belongs to the subclass.
     */
    onWorkerExit?: (code: number) => void;
  };
}

/**
 * §7.5/§22.8 (D7 cutover, Task 11): the supervisor-side session
 * lifecycle bridge. The SessionRegistry lives in the WORKER thread, but
 * `HostControlClient.openChannel` needs a SYNC supervisor-side
 * collaborator — this bridge owns the two halves that must stay local
 * (the durable cursor mirror feeds channel.open's resumeFromSequence;
 * the bind/dead/unbind bookkeeping forwards fire-and-forget into the
 * worker's registry, which no-ops unknown sessions).
 *
 * The §13 retention half is CONSERVATIVE: a worker-host control-socket
 * drop reports no retained sessions (the worker's in-process state cannot
 * be proven to the engine across the restart seam — §22.8's retained
 * in-process evidence is the LOCAL host's registry report), so the
 * engine's resume decides CLOSE per the §13 one-shot default.
 * closeRetained/cancelExecution forward through the worker bridge
 * (fatal connection-state / the executor's cancel path).
 */
export class SessionLifecycleBridge implements HostRetentionLifecycle {
  /** Mirror of the per-session durable-event cursor (monotonic max). */
  private readonly cursors = new Map<string, number>();

  constructor(
    private readonly dispatch: (sessionId: string, message: {
      kind: "channel-opened" | "channel-dead" | "channel-unbind"
        | "cancel-execution" | "close-retained";
      sessionId: string;
      opened?: { highestContiguousSequence: number };
    }) => void,
  ) {}

  getHighestContiguousSequence(sessionId: string): number {
    return this.cursors.get(sessionId) ?? 0;
  }

  observeDurableSequence(sessionId: string, sequence: number): void {
    const prior = this.cursors.get(sessionId) ?? 0;
    if (sequence > prior) {
      this.cursors.set(sessionId, sequence);
    }
  }

  bindChannel(
    sessionId: string,
    _socket: unknown,
    opened: { highestContiguousSequence: number },
  ): void {
    // The socket reference stays supervisor-side (the client owns the
    // transport); the worker's registry records liveness bookkeeping.
    this.dispatch(sessionId, {
      kind: "channel-opened",
      sessionId,
      opened: { highestContiguousSequence: opened.highestContiguousSequence },
    });
  }

  markChannelDead(sessionId: string): void {
    this.dispatch(sessionId, { kind: "channel-dead", sessionId });
  }

  unbindChannel(sessionId: string): { socket: unknown } | null {
    this.dispatch(sessionId, { kind: "channel-unbind", sessionId });
    // The socket object never crossed the boundary — nothing to return.
    return null;
  }

  // ---- §13 retention (A2): the conservative worker-host half ----

  markAllDisconnected(): void {
    // Retention bookkeeping only — the retained-set view stays empty
    // (see the class doc: the worker's in-process state is not provable
    // across the restart seam for worker-host compositions).
  }

  retainedSessionIds(): string[] {
    return [];
  }

  buildRetainedSummaries(): Array<{
    sessionId: string;
    state: string;
    capacityHeld: boolean;
  }> {
    return [];
  }

  rebindAfterReconcile(sessionId: string): number {
    return this.getHighestContiguousSequence(sessionId);
  }

  closeRetained(sessionId: string): void {
    // The worker's existing fatal teardown path (idempotent registry-side).
    this.dispatch(sessionId, { kind: "close-retained", sessionId });
  }

  cancelExecution(executionId: string): void {
    // Forward the §13 CANCEL_EXECUTION decision's cancellation into the
    // worker's executor (the existing execution.cancel handling).
    this.dispatch(executionId, { kind: "cancel-execution", sessionId: executionId });
  }
}

export abstract class WorkerHostAgentSupervisor extends AgentSupervisor {
  /** The spawned worker host (null before spawn / after stop). */
  protected host: AgentWorkerHost | null = null;

  /** The worker creational options (config thunk/value + callbacks). */
  private readonly workerOptions: NonNullable<
    WorkerHostSupervisorOptions["worker"]
  >;

  constructor(options: WorkerHostSupervisorOptions) {
    super({
      engineUrl: options.engineUrl,
      role: options.role,
      logger: options.logger,
      ...(options.poolSize !== undefined ? { poolSize: options.poolSize } : {}),
      ...(options.capabilities !== undefined
        ? { capabilities: options.capabilities }
        : {}),
      ...(options.reportedCapacity !== undefined
        ? { reportedCapacity: options.reportedCapacity }
        : {}),
      ...(options.ownerUserId !== undefined
        ? { ownerUserId: options.ownerUserId }
        : {}),
      ...(options.connection !== undefined ? { connection: options.connection } : {}),
      ...(options.path !== undefined ? { path: options.path } : {}),
    });
    this.workerOptions = options.worker;
  }

  // ==================== Worker pool ====================

  /**
   * Bring up the Agent worker (thread) pool: resolve the subclass's
   * creational config, spawn the host, and wire the §7.5/§22.8 session
   * lifecycle bridge into the base's registry seam. The base start() calls
   * this once, after auth, before the control socket opens.
   */
  protected override async spawnWorkers(): Promise<void> {
    // Resolve the creational config - a thunk may capture state that is
    // only available after construction (e.g. the fresh access token).
    const config = typeof this.workerOptions.config === "function"
      ? this.workerOptions.config()
      : this.workerOptions.config;
    // Protocol 12.1/12.3: forward every engine protocol.ack across the
    // worker boundary - the executor's outbox acknowledges the record
    // whose outbound frame carried the messageId (at-least-once). Wired
    // BEFORE the spawn: the base start() brings the pool up before the
    // socket opens, so the sink exists by the time the first ack can arrive.
    this.engineAckSink = (acknowledgedMessageId) => {
      this.host?.dispatchEngineAck(acknowledgedMessageId);
    };
    this.host = spawnAgentWorkerHost({
      onFrame: (frame) => this.routeWorkerFrame(frame),
      config,
      onExit: (code) => {
        if (this.workerOptions.onWorkerExit) {
          this.workerOptions.onWorkerExit(code);
        } else {
          this.log.warn(`Agent worker exited (code=${code}); restart deferred`);
        }
      },
      logger: this.log,
    });
    // §7.5/§22.8 (D7 cutover, Task 11): wire the session lifecycle bridge —
    // HostControlClient now binds the dedicated Agent Channel on every
    // session.open offer (the supervisor-side cursor mirror feeds
    // channel.open's resumeFromSequence; bind/dead/unbind bookkeeping
    // forwards fire-and-forget into the worker's registry, and the fatal
    // teardown rides the EXISTING connection-state seam).
    this.sessionRegistry = new SessionLifecycleBridge(
      (sessionId, message) => {
        if (message.kind === "channel-opened") {
          this.host?.dispatchChannelOpened(sessionId, message.opened!);
        } else if (message.kind === "channel-dead") {
          this.host?.dispatchChannelDead(sessionId);
        } else if (message.kind === "channel-unbind") {
          this.host?.dispatchChannelUnbind(sessionId);
        } else if (message.kind === "cancel-execution") {
          this.host?.dispatchCancelExecution(sessionId);
        } else if (message.kind === "close-retained") {
          this.host?.dispatchCloseRetained(sessionId);
        }
      },
    );
  }

  /** Tear the worker pool down. Called during {@link stop} before disconnect. */
  protected override async stopWorkers(): Promise<void> {
    await this.host?.stop();
    this.host = null;
  }

  // ==================== Inbound -> worker forwarding ====================

  // Seam 4 (worker-host compositions) = engine only: every unified command
  // frame is forwarded to the worker (as a legacy-shaped Envelope - the
  // worker's dispatch speaks that shape), and the worker's output frames are
  // routed onto the unified wire via the `onFrame` -> routeWorkerFrame sink
  // wired in spawnWorkers.

  protected override async onExecutionStart(
    frame: Parameters<ExecutionHandler>[0],
  ): Promise<void> {
    // The engine pushes BOTH payload shapes on execution.start:
    //  - conversation: ExecutionStartPayload -> forward as "execution.start"
    //    (the worker dispatches it to the inference executor).
    //  - orchestration: OrchestrationExecutionStartPayload (dispatchId +
    //    assignmentDigest) -> the worker's orchestration executor resolves
    //    the assignment from the session opened for the dispatch.
    const payload = frame.payload as
      | ExecutionStartPayload
      | OrchestrationExecutionStartPayload;
    this.forwardToWorkerEnvelope(
      makeEnvelope(
        "execution.start",
        AgentSupervisor.reconcileTransportSessionId(
          payload as Record<string, unknown>,
          frame.sessionId,
        ),
      ),
    );
  }

  protected override async onExecutionCancel(
    frame: Parameters<ExecutionCancelHandler>[0],
  ): Promise<void> {
    const payload = frame.payload as ExecutionCancelPayload;
    this.forwardToWorkerEnvelope(
      makeEnvelope(
        "execution.cancel",
        AgentSupervisor.reconcileTransportSessionId(
          payload as Record<string, unknown>,
          frame.sessionId,
        ),
      ),
    );
  }

  /**
   * A `session.open` the client dispatched after its own transport-side
   * bookkeeping (channel bind, opened reply). The Agent's registry needs it
   * to establish the model + tools for the session.
   */
  protected override onSessionOpen(payload: SessionOpenPayload): void {
    this.forwardToWorkerEnvelope(
      makeEnvelope("session.open", payload as Record<string, unknown>),
    );
  }

  /** A `session.close` the client dispatched - forward to the worker. */
  protected override onSessionClose(payload: { sessionId: string }): void {
    this.forwardToWorkerEnvelope(
      makeEnvelope("session.close", payload as Record<string, unknown>),
    );
  }

  /**
   * §22.3 (Task 11 fix): the inbound interaction-command family — forward the
   * raw frame to the worker; its switch routes execution.control /
   * execution.interaction / execution.control.request.resolved to the
   * executor's coordinator/controller arms.
   */
  protected override async onInteractionCommand(
    frame: ParsedUnifiedFrame,
  ): Promise<void> {
    this.forwardToWorkerEnvelope(
      frame as unknown as RawEnvelope,
    );
  }

  /**
   * §22.8 (D7): a session connection-state notification - forward across
   * the worker bridge so the worker stops the session runtime (fatal) or
   * flips its admission gate (ready=false/true). Repeated fatals are
   * idempotent worker-side (the registry teardown is a no-op once gone).
   */
  protected override onConnectionState(state: {
    sessionId: string;
    ready: boolean;
    fatal: boolean;
  }): void {
    if (!this.host) {
      this.log.warn(
        "Dropping connection-state; no worker spawned yet: session",
        state.sessionId,
      );
      return;
    }
    this.host.dispatchConnectionState(state);
  }

  protected override forwardToWorker(frame: RawEnvelope): void {
    if (!this.host) {
      this.log.warn("Dropping frame; no worker spawned yet:", frame.type);
      return;
    }
    this.host.dispatch(frame);
  }

  /**
   * Route a frame the Agent produced onto the unified wire. The optional
   * `onWorkerFrame` observer fires FIRST (the interactive composition
   * mirrors conversation-socket frames to the editor here) before the
   * engine post.
   */
  protected override async routeWorkerFrame(frame: Envelope): Promise<void> {
    this.workerOptions.onWorkerFrame?.(frame);
    await this.post(frame);
  }

  /**
   * The run PSK decoded from `host.opened` (32 bytes, process memory only -
   * never logged, never persisted). Handed across the worker-thread boundary
   * so the worker's SessionRegistry can unwrap the session's credential
   * envelopes (the encrypted model key) - the base default only reaches a
   * registry's `setPsk`, which the conservative bridge never implements, so
   * WITHOUT this override any session.open carrying credentials would fail
   * with MISSING_PSK in the worker and the execution turn would dead-end.
   * Dropped (with a warn) when the worker host is not spawned yet,
   * preserving the base no-op behavior in that gap.
   */
  protected override onPsk(psk: Uint8Array): void {
    if (!this.host) {
      this.log.warn("Dropping PSK; no worker spawned yet");
      return;
    }
    this.host.setPsk(psk);
  }
}