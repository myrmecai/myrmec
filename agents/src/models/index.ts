// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Core type contracts for the Agent SDK.
 *
 * These are the shapes §9.6.6 of dedicated-agents-and-workspaces.md flags as
 * "worth freezing early" — they ripple across protocol, transport, worker,
 * executor, and tools, so they are typed here first rather than inferred ad
 * hoc. Keep them minimal; add fields only when a real consumer needs them.
 */

/** How a Supervisor is acting on the wire. */
export type SupervisorRole = "HEADLESS" | "INTERACTIVE";

/** Minimal logger surface used across the SDK; `console` satisfies it. */
export interface Logger {
  debug(...args: unknown[]): void;
  info(...args: unknown[]): void;
  warn(...args: unknown[]): void;
  error(...args: unknown[]): void;
}

/** Workspace access mode (REQ-A-052: clone temp dir vs the open editor folder). */
export type WorkspaceAccessMode = "READ_WRITE" | "READ_ONLY";

/**
 * Auth state held by a Supervisor.
 * - Headless: `agentAccessToken`/`agentRefreshToken` come from the
 *   registration-key bootstrap; the user fields are absent.
 * - Interactive: user tokens come from loopback PKCE login, the agent
 *   credential is self-activated from them, and `ownerUserId` is bound.
 */
export interface AuthContext {
  /** AGENT principal — used on the control socket. */
  agentAccessToken: string;
  agentRefreshToken: string;
  agentTokenExpiresAt: number; // epoch ms

  /** USER principal — Interactive only (loopback PKCE). */
  userAccessToken?: string;
  userRefreshToken?: string;
  userTokenExpiresAt?: number; // epoch ms

  /** Owner binding — Interactive only; the agent serves only this user. */
  ownerUserId?: string;
}

/** Per-process Supervisor identity and current binding. */
export interface SupervisorContext {
  hostId: string;
  role: SupervisorRole;
  controlEndpoint: string; // wss://… engine replica URL
  agentId?: string; // assigned once registered
  conversationId?: string; // set while bound to a conversation
}

/** A resolved workspace the worker runs its tools against. */
export interface WorkspaceHandle {
  /** Absolute local path the tools are jailed within (REQ-A-050). */
  rootPath: string;
  accessMode: WorkspaceAccessMode;
  /** True once this worker holds the single-writer lease (server-side case). */
  writeLeaseHeld: boolean;
}

/** A single tool invocation, the canonical shape shared by the turn loop,
 * audit, and replay (REQ-A-070/072). */
export interface ToolCallRecord {
  /** Client-side id correlating the call with its result. */
  toolCallId: string;
  toolName: string;
  /** Arguments the model supplied (already parsed from the model's JSON). */
  args: Record<string, unknown>;
  /** Result payload, present once the tool has run. */
  result?: unknown;
  /** Set if the tool failed; mutually exclusive with `result`. */
  error?: string;
  startedAt: number; // epoch ms
  completedAt?: number; // epoch ms
}

/** A human-in-the-loop approval request emitted by a guarded tool action
 * (REQ-A-060). Presentation differs (engine HITL vs editor modal) but the
 * shape — and the engine recording of it — does not (REQ-A-062). */
export interface ApprovalRequest {
  /** Client request id; the matching `approval.decision` carries it back. */
  requestId: string;
  /** The tool call awaiting a decision. */
  toolCall: ToolCallRecord;
  /** Human-readable description of what will happen if approved. */
  summary: string;
}

/** The decision that resolves an `ApprovalRequest`. */
export interface ApprovalDecision {
  requestId: string;
  approved: boolean;
  /** Optional reason, recorded for audit. */
  reason?: string;
}

/** A unit of work assigned to a worker — either a workflow task or a
 * conversation turn. The worker treats both through the same turn loop. */
export interface Task {
  taskId: string;
  /** Provider/model code the worker must call directly (REQ-A-040/041). */
  model: string;
  /** Engine-assembled context: system prompt, history tail, tools, etc. */
  context: TaskContext;
  /** When true the worker must make tools deterministic (REQ-A-073). */
  replayMode?: boolean;
}

/** The engine-assembled context for one turn (UC-014 ordered contract). */
export interface TaskContext {
  systemPrompt: string;
  /** Verbatim recent history tail (≤ HISTORY_LIMIT), already assembled. */
  messages: TurnMessage[];
  /** Effective tool names available this turn (profile minus disabled). */
  toolNames: string[];
  /** Where the worker's tools operate. */
  workspace?: WorkspaceHandle;
  /** Opaque metadata the engine passes through (kb bindings, hitl mode…). */
  metadata?: Record<string, unknown>;
}

/** One message in the assembled turn context. */
export interface TurnMessage {
  role: "system" | "user" | "assistant" | "tool";
  content: string;
}

/** The terminal outcome of a task/turn. */
export interface TaskResult {
  taskId: string;
  status: "COMPLETE" | "FAILED" | "CANCELLED";
  /** Final assistant text, when COMPLETE. */
  completion?: string;
  /** Classified failure (REQ-A-023) — lets the engine choose retry vs fail. */
  failure?: {
    kind: "TRANSIENT" | "PERMANENT";
    finishReason: string; // e.g. PROVIDER_ERROR, TURN_TIMEOUT
    message: string;
  };
  /** Tool calls made during the turn, for audit/replay. */
  toolCalls: ToolCallRecord[];
}
