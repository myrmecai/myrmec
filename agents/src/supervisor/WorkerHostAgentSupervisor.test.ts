// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Unit tests for the worker-host composition supervisor (the intermediate
 * SDK class between `AgentSupervisor` and the concrete supervisors).
 *
 * Strategy: `spawnAgentWorkerHost` is module-mocked so no real worker
 * thread spawns - a FakeWorkerHost captures every dispatch call the
 * composition wires up, and a minimal concrete subclass (which stubs the
 * abstract auth/workspace seams) exposes the protected hooks for tests to
 * drive directly. The SessionLifecycleBridge is tested directly.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";

// The worker-host module mock must be installed BEFORE importing the
// supervisor (its constructor-time type imports are erased; the runtime
// import of spawnAgentWorkerHost resolves through the mock).
const recordedSpawns: Array<{
  onFrame: (frame: unknown) => void;
  config: unknown;
  onExit?: (code: number) => void;
  logger: unknown;
}> = [];

class FakeWorkerHost {
  readonly dispatched: unknown[] = [];
  readonly engineAcks: string[] = [];
  readonly connectionStates: Array<{
    sessionId: string;
    ready: boolean;
    fatal: boolean;
  }> = [];
  readonly channelOpened: Array<{
    sessionId: string;
    opened: { highestContiguousSequence: number };
  }> = [];
  readonly channelDead: string[] = [];
  readonly channelUnbind: string[] = [];
  readonly cancelExecutions: string[] = [];
  readonly closeRetained: string[] = [];
  readonly psks: Uint8Array[] = [];
  stopped = 0;

  dispatch(frame: unknown): void {
    this.dispatched.push(frame);
  }

  dispatchEngineAck(acknowledgedMessageId: string): void {
    this.engineAcks.push(acknowledgedMessageId);
  }

  dispatchConnectionState(state: {
    sessionId: string;
    ready: boolean;
    fatal: boolean;
  }): void {
    this.connectionStates.push(state);
  }

  dispatchChannelOpened(
    sessionId: string,
    opened: { highestContiguousSequence: number },
  ): void {
    this.channelOpened.push({ sessionId, opened });
  }

  dispatchChannelDead(sessionId: string): void {
    this.channelDead.push(sessionId);
  }

  dispatchChannelUnbind(sessionId: string): void {
    this.channelUnbind.push(sessionId);
  }

  dispatchCancelExecution(executionId: string): void {
    this.cancelExecutions.push(executionId);
  }

  dispatchCloseRetained(sessionId: string): void {
    this.closeRetained.push(sessionId);
  }

  setPsk(psk: Uint8Array): void {
    this.psks.push(psk);
  }

  async stop(): Promise<void> {
    this.stopped += 1;
  }
}

const fakeHosts: FakeWorkerHost[] = [];

vi.mock("../worker/AgentWorkerHost.js", async (importOriginal) => {
  const actual = await importOriginal<
    Record<string, unknown>
  >();
  return {
    ...actual,
    spawnAgentWorkerHost: (options: {
      onFrame: (frame: unknown) => void;
      config?: unknown;
      onExit?: (code: number) => void;
      logger?: unknown;
    }) => {
      recordedSpawns.push({
        onFrame: options.onFrame,
        config: options.config,
        onExit: options.onExit,
        logger: options.logger,
      });
      const host = new FakeWorkerHost();
      fakeHosts.push(host);
      return host as unknown as import("../worker/AgentWorkerHost.js").AgentWorkerHost;
    },
  };
});

import {
  WorkerHostAgentSupervisor,
  SessionLifecycleBridge,
  type WorkerHostSupervisorOptions,
} from "./WorkerHostAgentSupervisor.js";
import { makeEnvelope, type Envelope } from "../protocol/envelope.js";
import type {
  AuthContext,
  Logger,
  Task,
  WorkspaceHandle,
} from "../models/index.js";
import type { AgentWorkerConfig } from "../worker/agentWorkerProtocol.js";

/** Fresh per-test silent logger (spy-backed so warn behavior can be
 * asserted; each test gets its OWN instance so spy state never leaks). */
function newSilentLogger(): Logger {
  return {
    debug: vi.fn(),
    info: vi.fn(),
    warn: vi.fn(),
    error: vi.fn(),
  };
}

/** A concrete subclass whose ONLY responsibility is exposing protected
 * hooks + capturing what it receives (composition under test). */
class RecordingWorkerHostSupervisor extends WorkerHostAgentSupervisor {
  /** Frames the composition routed via routeWorkerFrame (through onWorkerFrame). */
  readonly workerFrames: unknown[] = [];
  /** The config thunk (or value) handed down by the test. */
  readonly handedConfig: AgentWorkerConfig | (() => AgentWorkerConfig);

  constructor(options: WorkerHostSupervisorOptions) {
    super(options);
    this.handedConfig = options.worker.config;
  }

  protected authenticate(): Promise<AuthContext> {
    return Promise.resolve({
      agentAccessToken: "tok",
      agentRefreshToken: "r",
      agentTokenExpiresAt: 0,
    });
  }

  protected getAccessToken(): Promise<string> {
    return Promise.resolve("tok");
  }

  protected refreshTokens(): Promise<string> {
    return Promise.resolve("tok");
  }

  protected reRegister(): Promise<string> {
    return Promise.resolve("tok");
  }

  protected resolveWorkspace(_task: Task): Promise<WorkspaceHandle> {
    throw new Error("not needed");
  }

  // ---- Public test hooks onto the protected composition surface ----

  spawnPool(): Promise<void> {
    return this.spawnWorkers();
  }

  stopPool(): Promise<void> {
    return this.stopWorkers();
  }

  ackSink(): ((acknowledgedMessageId: string) => void) | null {
    return this.engineAckSink;
  }

  registry(): SessionLifecycleBridge | undefined {
    return this.sessionRegistry as SessionLifecycleBridge | undefined;
  }

  spawnedHost(): unknown {
    return this.host;
  }

  startCmd(
    frame: Parameters<
      NonNullable<
        WorkerHostAgentSupervisor["onExecutionStart"]
      >
    >[0],
  ): Promise<void> {
    return this.onExecutionStart(frame);
  }

  cancelCmd(
    frame: Parameters<
      NonNullable<
        WorkerHostAgentSupervisor["onExecutionCancel"]
      >
    >[0],
  ): Promise<void> {
    return this.onExecutionCancel(frame);
  }

  sessionOpenCmd(payload: { sessionId: string }): void {
    this.onSessionOpen(payload as never);
  }

  sessionCloseCmd(payload: { sessionId: string }): void {
    this.onSessionClose(payload);
  }

  interactionCmd(frame: unknown): Promise<void> {
    return this.onInteractionCommand(
      frame as Parameters<
        NonNullable<
          WorkerHostAgentSupervisor["onInteractionCommand"]
        >
      >[0],
    );
  }

  connectionStateCmd(state: {
    sessionId: string;
    ready: boolean;
    fatal: boolean;
  }): void {
    this.onConnectionState(state);
  }

  forwardRaw(frame: unknown): void {
    this.forwardToWorker(frame as never);
  }

  routeOut(frame: Envelope): Promise<void> {
    return this.routeWorkerFrame(frame);
  }

  deliverPsk(psk: Uint8Array): void {
    this.onPsk(psk);
  }
}

const sessionId = "33333333-3333-4333-8333-333333333333";
const executionId = "44444444-4444-4444-8444-444444444444";

function baseWorkerOptions(
  config: AgentWorkerConfig | (() => AgentWorkerConfig),
): WorkerHostSupervisorOptions["worker"] {
  return {
    config,
    onWorkerFrame: (frame) => {
      void frame;
    },
  };
}

function lastSpawn(): { host: FakeWorkerHost; spawn: (typeof recordedSpawns)[number] } {
  expect(recordedSpawns.length).toBeGreaterThan(0);
  expect(fakeHosts.length).toBe(recordedSpawns.length);
  return { host: fakeHosts[fakeHosts.length - 1], spawn: recordedSpawns[recordedSpawns.length - 1] };
}

describe("SessionLifecycleBridge (conservative worker-host half)", () => {
  function capturing(expectedKinds: { onDispatch?: (sessionId: string, message: unknown) => void }) {
    const calls: Array<{ sessionId: string; message: unknown }> = [];
    const dispatch = (sessionId: string, message: unknown): void => {
      calls.push({ sessionId, message });
      expectedKinds.onDispatch?.(sessionId, message);
    };
    return { calls, bridge: new SessionLifecycleBridge(dispatch) };
  }

  it("getHighestContiguousSequence returns 0 when the session is unknown", () => {
    const { bridge } = capturing({});
    expect(bridge.getHighestContiguousSequence("unknown-session")).toBe(0);
  });

  it("getHighestContiguousSequence is a monotonic max of observed durable sequences", () => {
    const { bridge } = capturing({});
    bridge.observeDurableSequence("s1", 3);
    bridge.observeDurableSequence("s1", 7);
    bridge.observeDurableSequence("s1", 5);
    bridge.observeDurableSequence("s1", 7);
    bridge.observeDurableSequence("s2", 2);
    expect(bridge.getHighestContiguousSequence("s1")).toBe(7);
    expect(bridge.getHighestContiguousSequence("s2")).toBe(2);
    expect(bridge.getHighestContiguousSequence("s3")).toBe(0);
  });

  it("rebindAfterReconcile returns the session's durable cursor", () => {
    const { bridge } = capturing({});
    bridge.observeDurableSequence("s1", 11);
    expect(bridge.rebindAfterReconcile("s1")).toBe(11);
    expect(bridge.rebindAfterReconcile("missing")).toBe(0);
  });

  it("bindChannel dispatches a channel-opened forward with the opened cursor", () => {
    const { calls, bridge } = capturing({});
    bridge.bindChannel(sessionId, { rawSocket: true }, { highestContiguousSequence: 4 });
    expect(calls).toHaveLength(1);
    expect(calls[0].sessionId).toBe(sessionId);
    expect(calls[0].message).toEqual({
      kind: "channel-opened",
      sessionId,
      opened: { highestContiguousSequence: 4 },
    });
  });

  it("markChannelDead / unbindChannel dispatch the channel-dead / channel-unbind forwards", () => {
    const { calls, bridge } = capturing({});
    bridge.markChannelDead(sessionId);
    bridge.unbindChannel(sessionId);
    expect(calls.map((c) => (c.message as { kind: string }).kind)).toEqual([
      "channel-dead",
      "channel-unbind",
    ]);
    // unbind always returns null - the socket never crossed the boundary.
    expect(bridge.unbindChannel(sessionId)).toBeNull();
  });

  it("closeRetained and cancelExecution dispatch the §13 decision forwards", () => {
    const { calls, bridge } = capturing({});
    bridge.closeRetained(sessionId);
    bridge.cancelExecution(executionId);
    expect(calls).toEqual([
      { sessionId, message: { kind: "close-retained", sessionId } },
      { sessionId: executionId, message: { kind: "cancel-execution", sessionId: executionId } },
    ]);
  });

  it("reports the conservative retention contract: empty retained set + summaries, no-op markAllDisconnected", () => {
    const dispatchCalls: unknown[] = [];
    const bridge = new SessionLifecycleBridge((sid, msg) => {
      dispatchCalls.push([sid, msg]);
    });
    bridge.markAllDisconnected();
    expect(bridge.retainedSessionIds()).toEqual([]);
    expect(bridge.buildRetainedSummaries()).toEqual([]);
    // markAllDisconnected is bookkeeping only - no dispatch side effects.
    expect(dispatchCalls).toEqual([]);
  });
});

describe("WorkerHostAgentSupervisor (worker-host composition)", () => {
  let logger: Logger;
  let warns: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    recordedSpawns.length = 0;
    fakeHosts.length = 0;
    logger = newSilentLogger();
    warns = logger.warn as ReturnType<typeof vi.fn>;
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  function makeSupervisor(
    worker: WorkerHostSupervisorOptions["worker"],
    useLogger: Logger = logger,
  ): RecordingWorkerHostSupervisor {
    const options: WorkerHostSupervisorOptions = {
      engineUrl: "http://engine.local",
      role: "HEADLESS",
      worker,
    };
    if (logger) {
      options.logger = useLogger;
    }
    return new RecordingWorkerHostSupervisor(options);
  }

  it("spawnWorkers resolves a plain config value, wires engineAckSink BEFORE spawn, and registers the session lifecycle bridge", async () => {
    const config: AgentWorkerConfig = { engineUrl: "http://engine.local", maxIterations: 5 };
    const sup = makeSupervisor(baseWorkerOptions(config));
    await sup.spawnPool();
    const sinkAtSpawn = sup.ackSink();
    expect(sinkAtSpawn).not.toBeNull();

    const { host, spawn } = lastSpawn();
    expect(spawn.config).toEqual(config);
    expect(spawn.logger).toBe(logger);
    // The ack sink dispatches into the spawned host.
    sinkAtSpawn!("ack-1");
    expect(host.engineAcks).toEqual(["ack-1"]);

    // The lifecycle bridge is registered on the supervisor's registry seam
    // and its retain-contract is the conservative worker-host one.
    const registry = sup.registry();
    expect(registry).toBeInstanceOf(SessionLifecycleBridge);
    expect(registry!.retainedSessionIds()).toEqual([]);
  });

  it("spawnWorkers resolves a config THUNK at spawn time (fresh-token capture)", async () => {
    let token = "tok-1";
    const sup = makeSupervisor(
      baseWorkerOptions(() => ({ engineUrl: "http://engine.local", agentAccessToken: token })),
    );
    token = "tok-2";
    await sup.spawnPool();
    const { spawn } = lastSpawn();
    expect((spawn.config as AgentWorkerConfig).agentAccessToken).toBe("tok-2");
  });

  it("stopWorkers stops and clears the host", async () => {
    const sup = makeSupervisor(baseWorkerOptions({}));
    await sup.spawnPool();
    const { host } = lastSpawn();
    await sup.stopPool();
    expect(host.stopped).toBe(1);
    expect(sup.spawnedHost()).toBeNull();
  });

  it("onWorkerExit callback is honored when supplied; the default is a logged warn", async () => {
    const onWorkerExit = vi.fn();
    const sup = makeSupervisor({ config: {}, onWorkerExit });
    await sup.spawnPool();
    const { spawn } = lastSpawn();
    spawn.onExit!(7);
    expect(onWorkerExit).toHaveBeenCalledWith(7);

    const sup2 = makeSupervisor({ config: {} });
    await sup2.spawnPool();
    const spawn2 = recordedSpawns[recordedSpawns.length - 1];
    spawn2.onExit!(9);
    expect(warns).toHaveBeenCalledWith(
      "Agent worker exited (code=9); restart deferred",
    );
  });

  it("onExecutionStart reconciles the transport sessionId and forwards execution.start to the worker", async () => {
    const sup = makeSupervisor(baseWorkerOptions({}));
    await sup.spawnPool();
    const { host } = lastSpawn();
    // Engine convention (§8.1): payload sessionId = CONVERSATION id, the
    // envelope sessionId = transport session id - reconciliation re-stamps.
    await sup.startCmd({
      type: "execution.start",
      timestamp: "2026-01-01T00:00:00Z",
      sessionId: "sess-1",
      executionId,
      payload: { executionId, sessionId: "conversation-1" },
    } as unknown as Parameters<
      NonNullable<WorkerHostAgentSupervisor["onExecutionStart"]>
    >[0]);
    expect(host.dispatched).toHaveLength(1);
    const frame = host.dispatched[0] as { type: string; timestamp: string; payload: Record<string, unknown> };
    expect(frame.type).toBe("execution.start");
    expect(frame.payload.sessionId).toBe("sess-1");
    expect(frame.timestamp).toBeTruthy();
  });

  it("onExecutionCancel forwards execution.cancel to the worker with the reconciled sessionId", async () => {
    const sup = makeSupervisor(baseWorkerOptions({}));
    await sup.spawnPool();
    const { host } = lastSpawn();
    await sup.cancelCmd({
      type: "execution.cancel",
      timestamp: "2026-01-01T00:00:00Z",
      sessionId: "sess-9",
      executionId,
      payload: { executionId },
    } as unknown as Parameters<
      NonNullable<WorkerHostAgentSupervisor["onExecutionCancel"]>
    >[0]);
    expect(host.dispatched).toHaveLength(1);
    const frame = host.dispatched[0] as { type: string; payload: Record<string, unknown> };
    expect(frame.type).toBe("execution.cancel");
    expect(frame.payload.sessionId).toBe("sess-9");
  });

  it("onSessionOpen / onSessionClose forward as session.open / session.close envelopes", () => {
    const sup = makeSupervisor(baseWorkerOptions({}));
    return sup.spawnPool().then(() => {
      const { host } = lastSpawn();
      sup.sessionOpenCmd({ sessionId: "s-open", mode: "CONVERSATION" } as never);
      sup.sessionCloseCmd({ sessionId: "s-close" });
      expect(host.dispatched.map((f) => (f as { type: string }).type)).toEqual([
        "session.open",
        "session.close",
      ]);
    });
  });

  it("onInteractionCommand forwards the RAW frame (as a worker envelope) unchanged in shape", async () => {
    const sup = makeSupervisor(baseWorkerOptions({}));
    await sup.spawnPool();
    const { host } = lastSpawn();
    const raw = {
      type: "execution.control",
      timestamp: "2026-01-01T00:00:00Z",
      payload: { executionId, action: "PAUSE" },
    };
    await sup.interactionCmd(raw);
    expect(host.dispatched).toHaveLength(1);
    expect(host.dispatched[0]).toBe(raw);
  });

  it("onConnectionState dispatches into the worker; warns and drops when no host is spawned", async () => {
    const sup = makeSupervisor(baseWorkerOptions({}));
    await sup.spawnPool();
    const { host } = lastSpawn();
    sup.connectionStateCmd({ sessionId, ready: false, fatal: true });
    expect(host.connectionStates).toEqual([{ sessionId, ready: false, fatal: true }]);
  });

  it("onConnectionState warns and drops when no worker host is spawned", async () => {
    const sup = makeSupervisor(baseWorkerOptions({}));
    sup.connectionStateCmd({ sessionId, ready: false, fatal: true });
    expect(warns).toHaveBeenCalled();
  });

  it("forwardToWorker dispatches the raw envelope; drops with a warn pre-spawn", async () => {
    const raw = { type: "task.assign", timestamp: "2026-01-01T00:00:00Z", payload: {} };
    const sup = makeSupervisor(baseWorkerOptions({}));
    sup.forwardRaw(raw);
    expect(warns).toHaveBeenCalled();
    expect(recordedSpawns).toHaveLength(0);

    await sup.spawnPool();
    const { host } = lastSpawn();
    sup.forwardRaw(raw);
    expect(host.dispatched).toEqual([raw]);
  });

  it("routeWorkerFrame fires the onWorkerFrame observer FIRST, then posts", async () => {
    const observed: string[] = [];
    const sup = makeSupervisor({
      config: {},
      onWorkerFrame: (frame) => {
        observed.push((frame as { type: string }).type);
      },
    });
    const posted: string[] = [];
    // Spy the protected post(): must run AFTER the observer.
    (sup as unknown as { post: (frame: unknown) => Promise<void> }).post = (frame) => {
      observed.push("post:" + (frame as { type: string }).type);
      posted.push((frame as { type: string }).type);
      return Promise.resolve();
    };
    await sup.spawnPool();
    const frame = makeEnvelope("execution.delta", { content: "hi" });
    await sup.routeOut(frame);
    expect(observed).toEqual(["execution.delta", "post:execution.delta"]);
    expect(posted).toEqual(["execution.delta"]);
  });

  it("onPsk hands the PSK across the worker boundary (headless latent-gap fix); warns when no host", async () => {
    const psk = new Uint8Array(32).fill(7);
    const sup = makeSupervisor(baseWorkerOptions({}));
    // Pre-spawn: warn + drop (base-preserving behavior).
    sup.deliverPsk(psk);
    expect(warns).toHaveBeenCalled();

    await sup.spawnPool();
    const { host } = lastSpawn();
    sup.deliverPsk(psk);
    expect(host.psks).toHaveLength(1);
    expect(Array.from(host.psks[0])).toEqual(Array.from(psk));
  });

  it("spawned onFrame routes worker output frames through routeWorkerFrame", async () => {
    const sup = makeSupervisor(baseWorkerOptions({ engineUrl: "http://engine.local" }));
    const observed: string[] = [];
    (sup as unknown as { routeWorkerFrame: (frame: { type: string }) => Promise<void> }).routeWorkerFrame = (
      frame,
    ) => {
      observed.push(frame.type);
      return Promise.resolve();
    };
    await sup.spawnPool();

    // The spawn wired onFrame -> the (overridden) routeWorkerFrame.
    const { spawn, host } = lastSpawn();
    const outgoing = makeEnvelope("execution.complete", { executionId });
    await spawn.onFrame(outgoing);
    expect(observed).toEqual(["execution.complete"]);
    expect(host.dispatched).toHaveLength(0);
  });

  it("worker config thunk captures state resolved AFTER construction (fresh access token)", async () => {
    let token: string | undefined = undefined;
    const sup = makeSupervisor(
      baseWorkerOptions(() => ({ engineUrl: "http://engine.local", agentAccessToken: token })),
    );
    token = "fresh-token";
    await sup.spawnPool();
    const { spawn } = lastSpawn();
    expect((spawn.config as AgentWorkerConfig).agentAccessToken).toBe("fresh-token");
  });
});