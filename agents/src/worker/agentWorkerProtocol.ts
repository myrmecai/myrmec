// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * The message contract between a Supervisor (parent thread) and an Agent
 * worker (`worker_thread`).
 *
 * Locked shape (§9.7): the Supervisor owns the engine socket; the worker is
 * pure compute. So this channel is the worker's *only* link to the outside —
 * inbound control frames come **in**, and the frames the worker would put on
 * the wire go **out** for the Supervisor to route (engine for headless; editor
 * + engine for interactive — seam 4). Everything here must be
 * structured-cloneable: only plain data crosses a `worker_threads` boundary,
 * never class instances, closures, or sockets.
 */
import type { Envelope, RawEnvelope } from "../protocol/envelope.js";

/**
 * Parent → worker. Carries a decoded control frame for the worker to dispatch
 * to its task / conversation handlers. The `kind` discriminant leaves room for
 * future lifecycle messages (e.g. drain) without churn.
 */
export interface WorkerEnvelopeMessage {
  kind: "envelope";
  frame: RawEnvelope;
}

export type WorkerInbound = WorkerEnvelopeMessage;

/**
 * Worker → parent. A frame the worker produced (a `message.delta`,
 * `task.complete`, `approval.request`, …) for the Supervisor to route onto the
 * engine socket and, in interactive mode, the editor.
 */
export interface WorkerFrameMessage {
  kind: "frame";
  frame: Envelope;
}

export type WorkerOutbound = WorkerFrameMessage;

/** Plain config handed to a worker at spawn (must be structured-cloneable). */
export interface AgentWorkerConfig {
  /** Iteration cap forwarded to the executor. */
  maxIterations?: number;
  /** Max bytes an image attachment may be to inline as a native image part
   * (#103 Slice A). */
  maxImageBytes?: number;
  /** Engine base URL for retrieval and other agent-side RPC. */
  engineUrl?: string;
  /** Current agent access token for auth on engine calls. */
  agentAccessToken?: string;
  /** ChatModel factory mode: `'stub'` for deterministic E2E tests,
   * `'real'` for production (LangChain). Defaults to `'real'`. */
  chatModelMode?: "stub" | "real";
  /** SessionTool factory mode: `'stub'` for deterministic E2E tests,
   * `'real'` for production. Defaults to `'real'`. */
  sessionToolMode?: "stub" | "real";
  /** Path to a handler module (only used when mode='stub'). The worker
   * dynamically imports this file to load test-specific LLM/tool handlers. */
  stubModulePath?: string;
}
