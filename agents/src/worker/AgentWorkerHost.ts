// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * AgentWorkerHost: the parent-side handle to one Agent worker.
 *
 * Per §9.7 the Supervisor owns the engine socket and the worker is pure
 * compute, so this host is the bridge between them: it forwards inbound
 * control frames **into** the worker and routes the worker's outbound frames
 * **out** through {@link AgentWorkerHostOptions.onFrame} (the Supervisor wires
 * that to the engine socket for headless; to the editor + engine for
 * interactive — seam 4). It also observes worker `error`/`exit` so a crash is
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

  /** Terminate the worker thread. */
  async stop(): Promise<void> {
    await this.worker.terminate();
  }
}

export interface SpawnAgentWorkerHostOptions {
  onFrame: (frame: Envelope) => Promise<void> | void;
  config?: AgentWorkerConfig;
  onExit?: (code: number) => void;
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
    ...(options.logger ? { logger: options.logger } : {}),
  });
}
