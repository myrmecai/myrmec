// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * The message contract between a Supervisor (parent thread) and an Agent
 * worker (`worker_thread`).
 *
 * Locked shape (section 9.7): the Supervisor owns the engine socket; the worker is
 * pure compute. So this channel is the worker's *only* link to the outside -
 * inbound control frames come **in**, and the frames the worker would put on
 * the wire go **out** for the Supervisor to route (engine for headless; editor
 * + engine for interactive - seam 4). Everything here must be
 * structured-cloneable: only plain data crosses a `worker_threads` boundary,
 * never class instances, closures, or sockets.
 */
import type { Envelope, RawEnvelope } from "../protocol/envelope.js";

/**
 * Parent -> worker. Carries a decoded control frame for the worker to dispatch
 * to its task / conversation handlers.
 */
export interface WorkerEnvelopeMessage {
  kind: "envelope";
  frame: RawEnvelope;
}

/**
 * Parent -> worker. Delivers the run PSK (design
 * 2026-09-16-credential-envelope-delivery.md section 6/section 10) decoded from
 * `host.opened` on the parent's control socket. The worker stores it in
 * process memory only (the SessionRegistry vault) - it is never logged and
 * never persisted. Bytes travel as a plain array because structured clone
 * cannot cross the boundary with a typed-buffer view guarantee; the worker
 * reconstructs the Uint8Array.
 */
export interface WorkerPskMessage {
  kind: "psk";
  psk: number[];
}

/**
 * Parent -> worker. The engine's protocol.ack for a host->engine durable
 * frame (protocol 12.1/12.3), decoded on the parent's socket and handed
 * across the boundary so the worker's outbox can acknowledge the record
 * whose outbound frame carried `acknowledgedMessageId`.
 */
export interface WorkerEngineAckMessage {
  kind: "ack";
  acknowledgedMessageId: string;
}

/**
 * Parent -> worker (protocol §22.8 D7): the supervisor's connection-state
 * notification for one session. Carries the dedicated-channel/session
 * reachability seam across the thread boundary:
 *  - `ready: true`  — the required channel is bound AND reconciliation
 *    permits work (session admission gate open).
 *  - `ready: false` — pause admission for the session (control-socket loss
 *    with a live channel; reconciliation may later re-permit).
 *  - `fatal: true`  — the session must stop (bound channel died). Idempotent:
 *    repeated notifications for a stopping/stopped session are no-ops.
 */
export interface WorkerConnectionStateMessage {
  kind: "connection-state";
  sessionId: string;
  /** Required channel bound AND reconciliation permits work. */
  ready: boolean;
  /** Fatal loss (channel died / session must stop). Idempotent. */
  fatal: boolean;
}

/**
 * §7.5/§22.8 (D7 cutover, Task 11): the supervisor's dedicated channel
 * handshake completed for a session — the worker's SessionRegistry
 * records the binding (liveness bookkeeping; the socket itself stays
 * supervisor-side). Fire-and-forget: an unknown session is a no-op.
 */
export interface WorkerChannelOpenedMessage {
  kind: "channel-opened";
  sessionId: string;
  /** The channel.opened payload (cursor + limits) as structured-cloneable. */
  opened: { highestContiguousSequence: number };
}

/**
 * §22.8 (D7): the session's BOUND channel died — the registry clears its
 * binding mark (the fatal session teardown itself rides the existing
 * connection-state seam, which the supervisor emits alongside this).
 */
export interface WorkerChannelDeadMessage {
  kind: "channel-dead";
  sessionId: string;
}

/** Session close: the registry drops its channel binding (socket stays
 * supervisor-side; the client closes the transport). */
export interface WorkerChannelUnbindMessage {
  kind: "channel-unbind";
  sessionId: string;
}

/**
 * §13 (A2, Task 11): a CANCEL_EXECUTION resume decision's cancellation —
 * the worker's executor takes the existing execution.cancel path for the
 * named execution (the §13 decision IS the cancellation command).
 */
export interface WorkerCancelExecutionMessage {
  kind: "cancel-execution";
  /** The execution to cancel (the executor keys on executionId). */
  executionId: string;
}

/**
 * §13 (A2, Task 11): a CLOSE resume decision (or retention expiry) —
 * the worker runs the existing fatal session teardown for the session
 * (idempotent registry-side).
 */
export interface WorkerCloseRetainedMessage {
  kind: "close-retained";
  sessionId: string;
}

export type WorkerInbound =
  | WorkerEnvelopeMessage
  | WorkerPskMessage
  | WorkerEngineAckMessage
  | WorkerConnectionStateMessage
  | WorkerChannelOpenedMessage
  | WorkerChannelDeadMessage
  | WorkerChannelUnbindMessage
  | WorkerCancelExecutionMessage
  | WorkerCloseRetainedMessage;

/**
 * Worker -> parent. A frame the worker produced (an `execution.delta`,
 * `execution.complete`, `approval.request`, ...) for the Supervisor to route onto
 * the engine socket and, in interactive mode, the editor.
 */
export interface WorkerFrameMessage {
  kind: "frame";
  frame: Envelope;
}

/**
 * Worker -> parent. The engine's `protocol.ack` for a host->engine durable
 * frame, forwarded across the worker boundary so the executor's outbox
 * can acknowledge the matching record by messageId (protocol 12.1/12.3:
 * terminal/event records stay unacknowledged until this arrives).
 */
export interface WorkerAckMessage {
  kind: "ack";
  acknowledgedMessageId: string;
}

export type WorkerOutbound = WorkerFrameMessage | WorkerAckMessage;

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
  /** Feature 10 (section 17.1): workspace root for orchestration runs. Runs live
   * at <root>/runs/<runId>/<generation>/checkout. */
  workspaceRoot?: string;
  /** Feature 10 (section 16.3): durable outbox root (agent-local). */
  outboxRoot?: string;
  /** HITL (section 17.4): the orchestration project's autoHitlOnDestructive
   * matrix input (from MYRMEC_AUTO_HITL; conservative default true). */
  autoHitlOnDestructive?: boolean;
}
