// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * interactionTools (design 14.2, protocol 22.6/22.7): the interaction
 * loop's EXPLICIT tool allowlist.
 *
 * Six tools, exactly:
 *   get_execution_snapshot, get_recent_events, get_budget_usage,
 *   request_hold, request_continue, request_cancel.
 *
 * No workspace write tools, no arbitrary command execution, no helper
 * invocation, no policy change, no assignment mutation (22.6: "No
 * workspace write, arbitrary command, helper invocation, policy change,
 * or assignment mutation tool is exposed").
 *
 * The request_* tools persist `execution.control.request` through the
 * controller's durable seam (the outbox, before any wire emission) and
 * return the ENGINE's disposition - they never mutate hold/cancel state
 * themselves and NEVER wait for human confirmation inside the model
 * turn (22.7: CANCEL returns CONFIRMATION_REQUIRED + the pending
 * confirmation id; the answer completes normally; only an engine-issued
 * command changes execution state).
 */
import { randomUUID } from "node:crypto";
import type { Tool } from "../executor/types.js";
import type { Logger } from "../models/index.js";
import type { SafeExecutionEvent } from "./SafeExecutionEvent.js";
import { MAX_RECENT_EVENTS } from "./SafeExecutionEvent.js";
import type { ExecutionSnapshot } from "./ExecutionSnapshot.js";
import type {
  ExecutionControlRequestResolvedPayload,
  InteractionUsage,
} from "../protocol/unifiedFrames.js";

/** The tool context the controller binds per interaction (14.2). The
 * controller provides the immutable snapshot view, the bounded safe
 * event list, and the durable control-proposal seam. */
export interface InteractionToolContext {
  /** The CURRENT immutable execution snapshot (stale at view time). */
  getSnapshot(): Readonly<ExecutionSnapshot>;
  /** The most recent safe events, oldest first, bounded by `limit`. */
  getRecentEvents(limit: number): ReadonlyArray<SafeExecutionEvent>;
  /** Attributed chat usage observed so far in THIS interaction (null
   * tokens mean unknown; the budget usage tool surfaces it). */
  getInteractionUsage?(): Readonly<InteractionUsage> | null;
  /** Persist one durable control request and return the ENGINE's
   * disposition. Long waits ride the engine's resolution frame; the
   * tool's returned promise NEVER awaits a user decision (the
   * controller fails the wait at stop/deadline). The optional
   * explanation rides the persisted proposal payload (22.7). */
  requestControl(
    action: "HOLD" | "CONTINUE" | "CANCEL",
    explanation?: string,
  ): Promise<ExecutionControlRequestResolvedPayload>;
}

/** The exact allowlist (exported for executor/assertion use). */
export const INTERACTION_TOOL_NAMES: ReadonlySet<string> = new Set([
  "get_execution_snapshot",
  "get_recent_events",
  "get_budget_usage",
  "request_hold",
  "request_continue",
  "request_cancel",
]);

const noopLogger: Logger = { debug: () => {}, info: () => {}, warn: () => {}, error: () => {} };

/** The tool result of a request_* proposal: the engine's disposition
 * verbatim plus the pending-confirmation marker (22.7: the ANSWER must
 * say the action is pending - the model never fabricates success). */
export interface ControlRequestToolResult {
  /** The engine's resolution status (ACCEPTED / CONFIRMATION_REQUIRED /
   * REJECTED / DECLINED / EXPIRED). */
  status: ExecutionControlRequestResolvedPayload["status"];
  /** The durable proposal id (the engine's disposition correlates to it). */
  controlRequestId: string;
  /** True ONLY for CONFIRMATION_REQUIRED: the action awaits a user
   * decision through the engine; nothing changed yet. */
  pending: boolean;
  /** The engine-stamped confirmation expiry (CONFIRMATION_REQUIRED only). */
  expiresAt: string | null;
  /** The refusal code (22.7 catalogue) when rejected/declined/expired. */
  errorCode: string | null;
  /** Human-readable guidance for the model's answer. */
  message: string;
}

/** Build the six allowlisted tools. One tool set per INTERACTION TURN:
 * the context closures bind the controller's per-interaction slot. */
export function buildInteractionTools(
  context: InteractionToolContext,
  logger?: Logger,
): Tool[] {
  const log = logger ?? noopLogger;

  const getExecutionSnapshot: Tool = {
    name: "get_execution_snapshot",
    description:
      "Read the current immutable execution snapshot: identities (execution/" +
      "dispatch/workflow/run/step/task/attempt), hold overlay state, progress" +
      " counters, budget limits and totals, usage status. The snapshot is" +
      " STALE at view time: report version + capturedAt, never claim a live" +
      " lock or a final commit from it.",
    parameters: { type: "object", properties: {} },
    invoke: async () => context.getSnapshot(),
  };

  const getRecentEvents: Tool = {
    name: "get_recent_events",
    description:
      "Read the most recent safe execution events (bounded sanitized" +
      " metadata: checkpoint/verification/progress summaries). Takes an" +
      " optional integer limit (default 10, maximum " + MAX_RECENT_EVENTS + ").",
    parameters: {
      type: "object",
      properties: {
        limit: { type: "integer", description: "How many recent events (max " + MAX_RECENT_EVENTS + ")." },
      },
    },
    invoke: async (args) => {
      const raw = (args as { limit?: unknown }).limit;
      let limit = 10;
      if (typeof raw === "number" && Number.isInteger(raw) && raw > 0) {
        limit = raw;
      }
      return context.getRecentEvents(limit);
    },
  };

  const getBudgetUsage: Tool = {
    name: "get_budget_usage",
    description:
      "Read the attempt's budget view: effective limits, cumulative totals" +
      " (helper calls, tokens, verifier rejections) and the usage status" +
      " (KNOWN or UNKNOWN - UNKNOWN means the tokens could not be verified" +
      " and is never a zero).",
    parameters: { type: "object", properties: {} },
    invoke: async () => {
      const snapshot = context.getSnapshot();
      const interactionUsage =
        context.getInteractionUsage?.() ?? null;
      return {
        limits: snapshot.budgetLimits,
        total: snapshot.budgetTotal,
        usageStatus: snapshot.usageStatus,
        // The pending interaction's own attributed subtotal (22.6):
        // attribution only, never a second charge.
        interactionSubtotal: interactionUsage,
        helperCallsCompleted: snapshot.helperCallsCompleted,
        verifierRejections: snapshot.verifierRejections,
      };
    },
  };

  /** Shared proposal body: route through the controller's durable seam
   * and translate the disposition into the tool result. NEVER mutates
   * hold/cancel state; NEVER waits for a human decision. */
  const propose = async (
    action: "HOLD" | "CONTINUE" | "CANCEL",
    explanation?: string,
  ): Promise<ControlRequestToolResult> => {
    const disposition = await context.requestControl(action, explanation);
    if (disposition.status === "CONFIRMATION_REQUIRED") {
      log.info(
        `control request ${disposition.controlRequestId} (action ${action}) requires user confirmation (expires ${disposition.expiresAt ?? "engine-managed"})`,
      );
      return {
        status: disposition.status,
        controlRequestId: disposition.controlRequestId,
        pending: true,
        expiresAt: disposition.expiresAt ?? null,
        errorCode: null,
        message:
          "The " + action + " request was accepted and AWAITS USER CONFIRMATION" +
          " through the engine. Nothing has changed yet; the request id is " +
          disposition.controlRequestId + ". Say the action is pending; do not" +
          " claim it was executed.",
      };
    }
    if (disposition.status === "ACCEPTED") {
      return {
        status: "ACCEPTED",
        controlRequestId: disposition.controlRequestId,
        pending: false,
        expiresAt: null,
        errorCode: null,
        message:
          "The engine accepted the " + action + " request (command dispatched," +
          " request id " + disposition.controlRequestId + "). The observed" +
          " state change arrives through execution.control.state, not this" +
          " answer.",
      };
    }
    // REJECTED / DECLINED / EXPIRED: the refusal is honest, no
    // fabrication, no retry inside the turn.
    return {
      status: disposition.status,
      controlRequestId: disposition.controlRequestId,
      pending: false,
      expiresAt: disposition.expiresAt ?? null,
      errorCode: disposition.errorCode ?? null,
      message:
        "The engine " + disposition.status + " the " + action + " request" +
        (disposition.errorCode ? " (" + disposition.errorCode + ")" : "") +
        ". Report the refusal; do not retry it inside this turn.",
    };
  };

  const requestHold: Tool = {
    name: "request_hold",
    description:
      "Propose a temporary HOLD of the orchestration work through the" +
      " engine (a durable request; the engine decides). Use when the user" +
      " asks to pause the orchestration. Takes an optional explanation.",
    parameters: {
      type: "object",
      properties: {
        explanation: { type: "string", description: "Why the hold is proposed." },
      },
    },
    invoke: async (args) =>
      propose("HOLD", asExplanation((args as { explanation?: unknown }).explanation)),
  };

  const requestContinue: Tool = {
    name: "request_continue",
    description:
      "Propose resuming (CONTINUE) the orchestration work through the" +
      " engine (a durable request; the engine decides). Takes an optional" +
      " explanation.",
    parameters: {
      type: "object",
      properties: {
        explanation: { type: "string", description: "Why the continue is proposed." },
      },
    },
    invoke: async (args) =>
      propose("CONTINUE", asExplanation((args as { explanation?: unknown }).explanation)),
  };

  const requestCancel: Tool = {
    name: "request_cancel",
    description:
      "Propose CANCELLING this execution through the engine. The engine" +
      " requires a separate user confirmation: the tool returns" +
      " CONFIRMATION_REQUIRED with a pending request id and the answer" +
      " completes immediately - it never waits for the user inside this" +
      " turn. Takes an optional explanation.",
    parameters: {
      type: "object",
      properties: {
        explanation: { type: "string", description: "Why the cancel is proposed." },
      },
    },
    invoke: async (args) =>
      propose("CANCEL", asExplanation((args as { explanation?: unknown }).explanation)),
  };

  return [
    getExecutionSnapshot,
    getRecentEvents,
    getBudgetUsage,
    requestHold,
    requestContinue,
    requestCancel,
  ];
}

/** Bound and normalize the optional explanation (the wire schema allows
 * a nullable string; non-strings are dropped, oversized text is
 * truncated). */
function asExplanation(raw: unknown): string | undefined {
  if (typeof raw !== "string") {
    return undefined;
  }
  const bounded = raw.slice(0, 2000);
  return bounded.length > 0 ? bounded : undefined;
}

/** A proposal id (test/seam helper: the controller owns the real mint). */
export function newControlRequestId(): string {
  return randomUUID();
}