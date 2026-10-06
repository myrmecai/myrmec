// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * The cluster Supervisor: non-interactive, registration-key bootstrap.
 *
 * Implements seam 1 (authenticate) via the Engine HOST registration endpoint
 * and the token lifecycle (refresh / re-register) the unified client's
 * reconnect machinery calls into (REQ-A-001/002). Per section 9.7 it owns the
 * host-control socket (via the base's `HostControlClient`) and delegates
 * execution to an Agent worker thread: inbound unified frames are forwarded
 * into the worker, and the frames the worker emits are routed onto the
 * unified wire through the base's typed `post()` (seam 4 = engine only).
 * Seam 3 (workspace clone) is layered on by a later slice; `resolveWorkspace`
 * throws until then so the gap is loud.
 */
import { AgentSupervisor } from "./AgentSupervisor.js";
import { EngineHttpClient } from "../transport/httpClient.js";
import {
  AgentWorkerHost,
  spawnAgentWorkerHost,
} from "../worker/AgentWorkerHost.js";
import { makeEnvelope, type Envelope, type RawEnvelope } from "../protocol/envelope.js";
import type {
  AuthContext,
  Logger,
  Task,
  WorkspaceHandle,
} from "../models/index.js";
import type {
  ExecutionCancelPayload,
  ExecutionStartPayload,
  OrchestrationExecutionStartPayload,
  SessionOpenPayload,
} from "../protocol/unifiedFrames.js";
import type { HostRetentionLifecycle } from "./HostControlClient.js";

/**
 * §7.5/§22.8 (D7 cutover, Task 11): the supervisor-side session
 * lifecycle bridge. The SessionRegistry lives in the WORKER thread, but
 * `HostControlClient.openChannel` needs a SYNC supervisor-side
 * collaborator — this bridge owns the two halves that must stay local
 * (the durable cursor mirror feeds channel.open's resumeFromSequence;
 * the bind/dead/unbind bookkeeping forwards fire-and-forget into the
 * worker's registry, which no-ops unknown sessions).
 *
 * The §13 retention half is CONSERVATIVE: a headless control-socket drop
 * reports no retained sessions (the worker's in-process state cannot be
 * proven to the engine across the restart seam — §22.8's retained
 * in-process evidence is the LOCAL host's registry report), so the
 * engine's resume decides CLOSE per the §13 one-shot default.
 * closeRetained/cancelExecution forward through the worker bridge
 * (fatal connection-state / the executor's cancel path).
 */
class HeadlessSessionLifecycleBridge implements HostRetentionLifecycle {
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

  // ---- §13 retention (A2): the conservative headless half ----

  markAllDisconnected(): void {
    // Retention bookkeeping only — the retained-set view stays empty
    // (see the class doc: the worker's in-process state is not provable
    // across the restart seam headless).
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


export interface HeadlessAgentSupervisorOptions {
  engineUrl: string;
  registrationKey: string;
  sdkVersion?: string;
  metadata?: Record<string, unknown>;
  logger?: Logger;
  /**
   * Host capabilities (tools + runtime catalog) reported in `host.open` -
   * the supply side of reserve-time matching (replaces legacy provisions).
   */
  capabilities?: Record<string, unknown>;
  /**
   * CPU/RAM capacity the pool was auto-sized from (host.open + heartbeat).
   * Defaults to empty (the client sends what it has).
   */
  reportedCapacity?: Record<string, unknown>;
  /** Iteration cap forwarded to the worker's executor. */
  maxIterations?: number;
  /** Max bytes an image attachment may be to inline as a native image part
   * (#103 Slice A). */
  maxImageBytes?: number;
}

export class HeadlessAgentSupervisor extends AgentSupervisor {
  private readonly http: EngineHttpClient;
  private readonly maxIterations?: number;
  private readonly maxImageBytes?: number;
  private host: AgentWorkerHost | null = null;
  private accessToken: string | null = null;
  private refreshToken: string | null = null;

  constructor(options: HeadlessAgentSupervisorOptions) {
    super({
      engineUrl: options.engineUrl,
      role: "HEADLESS",
      logger: options.logger,
      // V1 headless pool size = 1; auto-sizing to N is a later slice.
      poolSize: 1,
      ...(options.capabilities !== undefined
        ? { capabilities: options.capabilities }
        : {}),
      ...(options.reportedCapacity !== undefined
        ? { reportedCapacity: options.reportedCapacity }
        : {}),
    });
    this.http = new EngineHttpClient({
      engineUrl: options.engineUrl,
      registrationKey: options.registrationKey,
      sdkVersion: options.sdkVersion,
      metadata: options.metadata,
    });
    this.maxIterations = options.maxIterations;
    this.maxImageBytes = options.maxImageBytes;
  }

  protected async authenticate(): Promise<AuthContext> {
    return this.register();
  }

  /** Register (or re-register) and capture the new token pair + host id.
   * Unified protocol 4.1/19.1: registration resolves the DURABLE host row
   * (hostId) - no instance row, no Agent row. The live host instance is
   * created later by host.open; the serving Agent row is minted at
   * session.opened. */
  private async register(): Promise<AuthContext> {
    this.log.info("Registering with Engine:", this.engineUrl);
    const res = await this.http.register();
    this.accessToken = res.accessToken;
    this.refreshToken = res.refreshToken;
    this.ctx.hostId = res.hostId;
    this.log.info("Registered as agent host:", res.hostId);

    this.auth = {
      agentAccessToken: res.accessToken,
      agentRefreshToken: res.refreshToken,
      // Engine owns expiry; the close-code path (4001) drives refresh, so a
      // precise local expiry is not required here.
      agentTokenExpiresAt: 0,
    };
    return this.auth;
  }

  protected async getAccessToken(): Promise<string> {
    if (!this.accessToken) {
      throw new Error("No access token; authenticate() not completed");
    }
    return this.accessToken;
  }

  protected async refreshTokens(): Promise<string> {
    if (!this.refreshToken) {
      throw new Error("No refresh token available");
    }
    this.log.debug("Refreshing tokens...");
    const res = await this.http.refresh(this.refreshToken);
    this.accessToken = res.accessToken;
    this.refreshToken = res.refreshToken;
    if (this.auth) {
      this.auth.agentAccessToken = res.accessToken;
      this.auth.agentRefreshToken = res.refreshToken;
    }
    return this.accessToken;
  }

  protected async reRegister(): Promise<string> {
    await this.register();
    return this.accessToken!;
  }

  // ==================== Worker pool ====================

  /**
   * Bring up the Agent worker (thread) pool. V1 headless pool size = 1
   * (config, not a
   * seam - section 9.3); auto-sizing to N is a later slice.
   */
  protected override async spawnWorkers(): Promise<void> {
    // Protocol 12.1/12.3: forward every engine protocol.ack across the
    // worker boundary - the executor's outbox acknowledges the record
    // whose outbound frame carried the messageId (at-least-once).
    this.engineAckSink = (acknowledgedMessageId) => {
      this.host?.dispatchEngineAck(acknowledgedMessageId);
    };
    this.host = spawnAgentWorkerHost({
      onFrame: (frame) => this.routeWorkerFrame(frame),
      config: {
        engineUrl: this.engineUrl,
        agentAccessToken: this.accessToken ?? undefined,
        ...(this.maxIterations !== undefined ? { maxIterations: this.maxIterations } : {}),
        ...(this.maxImageBytes !== undefined ? { maxImageBytes: this.maxImageBytes } : {}),
        // Execution provider selection (defaults to 'real' in production).
        // E2E tests set MYRMEC_LLM_EXECUTION=stub and MYRMEC_TOOL_EXECUTION=stub.
        ...(process.env.MYRMEC_LLM_EXECUTION ? { chatModelMode: process.env.MYRMEC_LLM_EXECUTION as "stub" | "real" } : {}),
        ...(process.env.MYRMEC_TOOL_EXECUTION ? { sessionToolMode: process.env.MYRMEC_TOOL_EXECUTION as "stub" | "real" } : {}),
        ...(process.env.MYRMEC_STUB_MODULE ? { stubModulePath: process.env.MYRMEC_STUB_MODULE } : {}),
        // Feature 10 (section 17.1/section 16.3): orchestration workspace + outbox
        // roots. Defaults per design section 17.1: /tmp/myrmec on POSIX,
        // %TEMP%\myrmec on Windows.
        workspaceRoot:
          process.env.MYRMEC_WORKSPACE_ROOT ??
          (process.platform === "win32"
            ? `${process.env.TEMP ?? "C:\\Windows\\Temp"}\\myrmec`
            : "/tmp/myrmec"),
        outboxRoot:
          process.env.MYRMEC_OUTBOX_ROOT ??
          `${process.env.MYRMEC_WORKSPACE_ROOT ?? (process.platform === "win32" ? `${process.env.TEMP ?? "C:\\Windows\\Temp"}\\myrmec` : "/tmp/myrmec")}/outbox`,
        // HITL (section 17.4): the orchestration project's autoHitlOnDestructive
        // matrix input. E2E sets MYRMEC_AUTO_HITL=true|false; the
        // conservative default (suspend on destructive) applies otherwise.
        ...(process.env.MYRMEC_AUTO_HITL
          ? { autoHitlOnDestructive: process.env.MYRMEC_AUTO_HITL === "true" }
          : {}),
      },
      onExit: (code) =>
        this.log.warn(`Agent worker exited (code=${code}); restart deferred`),
      logger: this.log,
    });
    // §7.5/§22.8 (D7 cutover, Task 11): wire the session lifecycle bridge —
    // HostControlClient now binds the dedicated Agent Channel on every
    // session.open offer (the supervisor-side cursor mirror feeds
    // channel.open's resumeFromSequence; bind/dead/unbind bookkeeping
    // forwards fire-and-forget into the worker's registry, and the fatal
    // teardown rides the EXISTING connection-state seam).
    this.sessionRegistry = new HeadlessSessionLifecycleBridge(
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

  protected override async stopWorkers(): Promise<void> {
    await this.host?.stop();
    this.host = null;
  }

  // ==================== Inbound -> worker forwarding ====================

  // Seam 4 (headless) = engine only: every unified command frame is forwarded
  // to the worker (as a legacy-shaped Envelope - the worker's dispatch speaks
  // that shape), and the worker's output frames are routed onto the unified
  // wire via the `onFrame` -> routeWorkerFrame sink wired in spawnWorkers.

  protected override async onExecutionStart(
    frame: Parameters<
      import("./HostControlClient.js").ExecutionHandler
    >[0],
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
    frame: Parameters<
      import("./HostControlClient.js").ExecutionCancelHandler
    >[0],
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
    frame: import("../protocol/unifiedFrames.js").ParsedUnifiedFrame,
  ): Promise<void> {
    this.forwardToWorkerEnvelope(
      frame as unknown as import("../protocol/envelope.js").RawEnvelope,
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

  protected override async routeWorkerFrame(frame: Envelope): Promise<void> {
    await this.post(frame);
  }

  protected async resolveWorkspace(_task: Task): Promise<WorkspaceHandle> {
    // Seam 3 (clone task.context.workspace -> temp dir) - implemented by the
    // workspace/executor slice.
    throw new Error("resolveWorkspace not implemented yet (executor slice)");
  }
}
