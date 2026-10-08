// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * The cluster Supervisor: non-interactive, registration-key bootstrap.
 *
 * Slim composition on the SDK's {@link WorkerHostAgentSupervisor}: this
 * subclass owns ONLY its auth seam (Engine HOST registration + the token
 * lifecycle the unified client's reconnect machinery calls into - REQ-A-001/002)
 * and the workspace gap (`resolveWorkspace` throws until the executor slice
 * lands) - ALL worker-host composition behavior (spawn/stop, the ack +
 * session-lifecycle bridge wiring, inbound -> worker forwarding) lives once
 * in the intermediate class, fed here as a creational `worker.config` thunk
 * that reads the same environment variables the former inline spawn did.
 *
 * Seam 3 (workspace clone) is layered on by a later slice; `resolveWorkspace`
 * throws until then so the gap is loud.
 */
import { EngineHttpClient } from "../transport/httpClient.js";
import { WorkerHostAgentSupervisor } from "./WorkerHostAgentSupervisor.js";
import type {
  AuthContext,
  Logger,
  Task,
  WorkspaceHandle,
} from "../models/index.js";
import type { AgentWorkerConfig } from "../worker/agentWorkerProtocol.js";

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

export class HeadlessAgentSupervisor extends WorkerHostAgentSupervisor {
  private readonly http: EngineHttpClient;
  private accessToken: string | null = null;
  private refreshToken: string | null = null;

  constructor(options: HeadlessAgentSupervisorOptions) {
    // V1 headless pool size = 1; auto-sizing to N is a later slice. The
    // worker creational config is a THUNK so it captures the fresh token
    // at spawn time (registration happens between construction and spawn)
    // and reads the same environment variables the former inline spawn did.
    super({
      engineUrl: options.engineUrl,
      role: "HEADLESS",
      logger: options.logger,
      poolSize: 1,
      ...(options.capabilities !== undefined
        ? { capabilities: options.capabilities }
        : {}),
      ...(options.reportedCapacity !== undefined
        ? { reportedCapacity: options.reportedCapacity }
        : {}),
      worker: {
        config: (): AgentWorkerConfig => ({
          engineUrl: options.engineUrl,
          agentAccessToken: this.accessToken ?? undefined,
          ...(options.maxIterations !== undefined
            ? { maxIterations: options.maxIterations }
            : {}),
          ...(options.maxImageBytes !== undefined
            ? { maxImageBytes: options.maxImageBytes }
            : {}),
          // Execution provider selection (defaults to 'real' in production).
          // E2E tests set MYRMEC_LLM_EXECUTION=stub and MYRMEC_TOOL_EXECUTION=stub.
          ...(process.env.MYRMEC_LLM_EXECUTION
            ? { chatModelMode: process.env.MYRMEC_LLM_EXECUTION as "stub" | "real" }
            : {}),
          ...(process.env.MYRMEC_TOOL_EXECUTION
            ? { sessionToolMode: process.env.MYRMEC_TOOL_EXECUTION as "stub" | "real" }
            : {}),
          ...(process.env.MYRMEC_STUB_MODULE
            ? { stubModulePath: process.env.MYRMEC_STUB_MODULE }
            : {}),
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
        }),
      },
    });
    this.http = new EngineHttpClient({
      engineUrl: options.engineUrl,
      registrationKey: options.registrationKey,
      sdkVersion: options.sdkVersion,
      metadata: options.metadata,
    });
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

  protected async resolveWorkspace(_task: Task): Promise<WorkspaceHandle> {
    // Seam 3 (clone task.context.workspace -> temp dir) - implemented by the
    // workspace/executor slice.
    throw new Error("resolveWorkspace not implemented yet (executor slice)");
  }
}
