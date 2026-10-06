// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * AgentWorkerHost: the parent-side handle to one Agent worker.
 *
 * Per section 9.7 the Supervisor owns the engine socket and the worker is pure
 * compute, so this host is the bridge between them: it forwards inbound
 * control frames **into** the worker and routes the worker's outbound frames
 * **out** through {@link AgentWorkerHostOptions.onFrame} (the Supervisor wires
 * that to the engine socket for headless; to the editor + engine for
 * interactive - seam 4). It also observes worker `error`/`exit` so a crash is
 * contained (REQ-A-085) and the Supervisor can decide on restart.
 *
 * The host depends only on a minimal {@link WorkerLike} surface so it is
 * unit-testable with a fake; {@link spawnAgentWorkerHost} wires up a real
 * `worker_thread`.
 */
import { Worker } from "node:worker_threads";
import type { Envelope, RawEnvelope } from "../protocol/envelope.js";
import type { Logger } from "../models/index.js";
import { resolveWorkerSource } from "./resolveWorkerEntry.js";
import type {
  AgentWorkerConfig,
  WorkerInbound,
  WorkerOutbound,
} from "./agentWorkerProtocol.js";

/** The slice of `worker_threads.Worker` the host needs; a node `Worker`
 * satisfies it, and a fake can stand in for tests. */
export interface WorkerLike {
  postMessage(value: unknown): void;
  on(event: "message", listener: (value: unknown) => void): unknown;
  on(event: "error", listener: (err: Error) => void): unknown;
  on(event: "exit", listener: (code: number) => void): unknown;
  terminate(): Promise<number> | void;
}

export interface AgentWorkerHostOptions {
  worker: WorkerLike;
  /** Route a frame the worker emitted (the Supervisor's seam-4 sink). */
  onFrame: (frame: Envelope) => Promise<void> | void;
  /**
   * The engine's protocol.ack for a host->engine durable frame, forwarded
   * from the worker (protocol 12.1/12.3) so the supervisor's outbox seam
   * can acknowledge the matching record by messageId. Optional.
   */
  onEngineAck?: (acknowledgedMessageId: string) => void;
  /** Observe worker exit; restart policy belongs to the Supervisor. */
  onExit?: (code: number) => void;
  logger?: Logger;
}

export class AgentWorkerHost {
  private readonly worker: WorkerLike;
  private readonly log: Logger;

  constructor(options: AgentWorkerHostOptions) {
    this.worker = options.worker;
    this.log = options.logger ?? console;

    this.worker.on("message", (value: unknown) => {
      const message = value as WorkerOutbound;
      if (message?.kind === "frame") {
        void Promise.resolve(options.onFrame(message.frame)).catch((err) =>
          this.log.error("Failed to route worker frame", err),
        );
        return;
      }
      if (message?.kind === "ack") {
        options.onEngineAck?.(message.acknowledgedMessageId);
        return;
      }
      // §22.8 (D7): connection-state is parent->worker ONLY - a worker
      // posting it is a protocol violation; the strict outbound union keeps
      // it untypeable, this guard keeps it unrouteable.
      if ((value as { kind?: string })?.kind === "connection-state") {
        this.log.warn(
          "AgentWorkerHost: worker posted a parent-only connection-state message - ignored",
          value,
        );
        return;
      }
      this.log.warn("AgentWorkerHost: unknown worker message", value);
    });

    this.worker.on("error", (err: Error) => {
      // A worker crash surfaces here, contained to its isolate (REQ-A-085).
      this.log.error("Agent worker error (contained)", err);
    });

    this.worker.on("exit", (code: number) => {
      this.log.info(`Agent worker exited: code=${code}`);
      options.onExit?.(code);
    });
  }

  /** Forward an inbound control frame into the worker. */
  dispatch(frame: RawEnvelope): void {
    const message: WorkerInbound = { kind: "envelope", frame };
    this.worker.postMessage(message);
  }

  /**
   * Hand the run PSK (design 2026-09-16 section 6/section 10) to the worker. The bytes
   * cross as a plain array (structured clone); the worker reconstructs the
   * Uint8Array and stores it in process memory only. MUST be called after
   * spawn and BEFORE any session.open carrying credential envelopes.
   */
  setPsk(psk: Uint8Array): void {
    const message: WorkerInbound = { kind: "psk", psk: Array.from(psk) };
    this.worker.postMessage(message);
  }

  /**
   * Forward the engine's protocol.ack INTO the worker (protocol
   * 12.1/12.3): the executor's outbox acknowledges the record whose
   * outbound frame carried the messageId.
   */
  dispatchEngineAck(acknowledgedMessageId: string): void {
    const message: WorkerInbound = { kind: "ack", acknowledgedMessageId };
    this.worker.postMessage(message);
  }

  /**
   * §22.8 (D7): forward the supervisor's connection-state notification for
   * one session INTO the worker (`{sessionId, ready, fatal}`). `fatal: true`
   * stops the session's runtime exactly once (the worker's existing session
   * teardown); `ready: false` suspends and `ready: true` resumes admission.
   */
  dispatchConnectionState(state: {
    sessionId: string;
    ready: boolean;
    fatal: boolean;
  }): void {
    const message: WorkerInbound = {
      kind: "connection-state",
      sessionId: state.sessionId,
      ready: state.ready,
      fatal: state.fatal,
    };
    this.worker.postMessage(message);
  }

  /**
   * §7.5/§22.8 (D7 cutover, Task 11): forward the supervisor-side channel
   * handshake result into the worker — the SessionRegistry records the
   * binding (liveness bookkeeping). Fire-and-forget (unknown session is
   * a registry no-op); the socket itself stays supervisor-side.
   */
  dispatchChannelOpened(sessionId: string, opened: {
    highestContiguousSequence: number;
  }): void {
    const message: WorkerInbound = { kind: "channel-opened", sessionId, opened };
    this.worker.postMessage(message);
  }

  /** §22.8 (D7): the session's bound channel died — the registry clears
   * its binding mark (the fatal teardown rides connection-state). */
  dispatchChannelDead(sessionId: string): void {
    const message: WorkerInbound = { kind: "channel-dead", sessionId };
    this.worker.postMessage(message);
  }

  /** Session close: the registry drops its channel binding. */
  dispatchChannelUnbind(sessionId: string): void {
    const message: WorkerInbound = { kind: "channel-unbind", sessionId };
    this.worker.postMessage(message);
  }

  /**
   * §13 (A2, Task 11): a CANCEL_EXECUTION resume decision — the worker's
   * executor takes the existing execution.cancel path for the execution.
   */
  dispatchCancelExecution(executionId: string): void {
    const message: WorkerInbound = { kind: "cancel-execution", executionId };
    this.worker.postMessage(message);
  }

  /** §13 (A2, Task 11): a CLOSE resume decision — the worker runs the
   * existing fatal session teardown (idempotent registry-side). */
  dispatchCloseRetained(sessionId: string): void {
    const message: WorkerInbound = { kind: "close-retained", sessionId };
    this.worker.postMessage(message);
  }

  /** Terminate the worker thread. */
  async stop(): Promise<void> {
    await this.worker.terminate();
  }
}

export interface SpawnAgentWorkerHostOptions {
  onFrame: (frame: Envelope) => Promise<void> | void;
  config?: AgentWorkerConfig;
  onExit?: (code: number) => void;
  onEngineAck?: (acknowledgedMessageId: string) => void;
  logger?: Logger;
}

/** Spawn a real `worker_thread` running the Agent body and wrap it in a host. */
export function spawnAgentWorkerHost(
  options: SpawnAgentWorkerHostOptions,
): AgentWorkerHost {
  const { spec, options: workerOptions } = resolveWorkerSource(
    import.meta.url,
    "agent-worker-entry",
  );
  const worker = new Worker(spec, {
    ...workerOptions,
    workerData: options.config ?? {},
  });
  return new AgentWorkerHost({
    worker,
    onFrame: options.onFrame,
    ...(options.onExit ? { onExit: options.onExit } : {}),
    ...(options.onEngineAck ? { onEngineAck: options.onEngineAck } : {}),
    ...(options.logger ? { logger: options.logger } : {}),
  });
}
