// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * The cluster Supervisor: non-interactive, registration-key bootstrap.
 *
 * Implements seam 1 (authenticate) via the Engine registration endpoint and
 * the token lifecycle (refresh / re-register) the reconnect policy calls into
 * (REQ-A-001/002). Per §9.7 it owns the engine socket and delegates execution
 * to an Agent worker thread: inbound control frames are forwarded into the
 * worker, and the frames the worker emits are routed straight onto the socket
 * (seam 4 = engine only). Seam 3 (workspace clone) is layered on by a later
 * slice; `resolveWorkspace` throws until then so the gap is loud.
 */
import { AgentSupervisor } from "./AgentSupervisor.js";
import { EngineHttpClient } from "../transport/httpClient.js";
import {
  AgentWorkerHost,
  spawnAgentWorkerHost,
} from "../worker/AgentWorkerHost.js";
import type { RawEnvelope } from "../protocol/envelope.js";
import type { AgentProvisions } from "../protocol/hostFrames.js";
import type { AuthContext, Logger, Task, WorkspaceHandle } from "../models/index.js";

export interface HeadlessAgentSupervisorOptions {
  engineUrl: string;
  registrationKey: string;
  sdkVersion?: string;
  metadata?: Record<string, unknown>;
  logger?: Logger;
  /** Tools + runtime this host advertises as installed (host.announce). */
  provisions?: AgentProvisions;
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
      ...(options.provisions !== undefined ? { provisions: options.provisions } : {}),
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

  /** Register (or re-register) and capture the new token pair + host id. */
  private async register(): Promise<AuthContext> {
    this.log.info("Registering with Engine:", this.engineUrl);
    const res = await this.http.register();
    this.accessToken = res.accessToken;
    this.refreshToken = res.refreshToken;
    this.ctx.hostId = res.instanceId;
    this.ctx.agentId = res.instanceId;
    this.log.info("Registered as instance:", res.instanceId);

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
    this.log.debug("Refreshing tokens…");
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

  protected async spawnWorkers(): Promise<void> {
    // V1 headless pool size = 1 for this slice; auto-sizing to N is a later
    // slice (the pool count is config, not a seam — §9.3).
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
      },
      onExit: (code) =>
        this.log.warn(`Agent worker exited (code=${code}); restart deferred`),
      logger: this.log,
    });
  }

  protected async stopWorkers(): Promise<void> {
    await this.host?.stop();
    this.host = null;
  }

  // ==================== Envelope forwarding ====================

  // Seam 4 (headless) = engine only: every control frame is forwarded to the
  // worker, and the worker's output frames are routed by type onto the control
  // or conversation socket via the `onFrame` → routeWorkerFrame sink wired in
  // spawnWorkers.
  protected async onTaskAssign(frame: RawEnvelope): Promise<void> {
    this.forwardToWorker(frame);
  }

  protected async onTaskCancel(frame: RawEnvelope): Promise<void> {
    this.forwardToWorker(frame);
  }

  protected async onConversationTurnAssign(frame: RawEnvelope): Promise<void> {
    this.forwardToWorker(frame);
  }

  protected async onApprovalDecision(frame: RawEnvelope): Promise<void> {
    this.forwardToWorker(frame);
  }

  protected forwardToWorker(frame: RawEnvelope): void {
    if (!this.host) {
      this.log.warn("Dropping frame; no worker spawned yet:", frame.type);
      return;
    }
    this.host.dispatch(frame);
  }

  protected async resolveWorkspace(_task: Task): Promise<WorkspaceHandle> {
    // Seam 3 (clone task.context.workspace → temp dir) — implemented by the
    // workspace/executor slice.
    throw new Error("resolveWorkspace not implemented yet (executor slice)");
  }
}
