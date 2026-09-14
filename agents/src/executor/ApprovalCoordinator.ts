// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Human-in-the-loop approval coordinator.
 *
 * Sits between an in-flight conversational turn and the engine: a handler's
 * `requestApproval(...)` emits an `approval.request` frame, registers a pending
 * wait keyed by a client-generated request id, and the supervisor's inbound
 * router calls {@link ApprovalCoordinator.resolve} when the matching
 * `approval.decision` arrives. One coordinator is shared by every in-flight
 * turn; the per-id waits keep concurrent requests isolated (REQ-A-060/062).
 */
import { randomUUID } from "node:crypto";
import type { Logger } from "../models/index.js";
import type { Envelope } from "../protocol/envelope.js";
import { approvalRequest } from "../protocol/conversationFrames.js";
import type { ApprovalDecisionWire } from "../protocol/conversationFrames.js";
import type { ExecutionApprovalRequestedPayload } from "../protocol/unifiedFrames.js";
import type { ExecutionFrameSender } from "./ExecutionFrameSender.js";

/** The outcome of a {@link ApprovalCoordinator.requestApproval} call. */
export interface ApprovalOutcome {
  /** APPROVED / REJECTED / EXPIRED (engine `ApprovalStatus` string). */
  decision: string;
  approved: boolean;
  rejected: boolean;
  expired: boolean;
  comment?: string;
  approverUserId?: string;
  requestMessageId?: string;
  responseMessageId?: string;
}

/** Options for a single approval request. */
export interface RequestApprovalOptions {
  /** Legacy conversation correlation id; still used by the conversation
   *  path until Task 6 deletes it. */
  conversationId?: string;
  /** Unified execution correlation id. When present, the coordinator emits
   *  `execution.approval.requested` instead of the legacy `approval.request`. */
  executionId?: string;
  /** Short human-readable summary rendered on the approval card. */
  content?: string;
  /** Free-form metadata serialised to `payloadJson` (legacy) or to
   *  `action.summary` (unified); the coordinator always injects
   *  `clientRequestId` so the engine can correlate via the persisted row
   *  even if the live frame is missed. */
  payload?: Record<string, unknown>;
  /** Milliseconds to await a decision before rejecting with
   * {@link ApprovalTimeoutError}. Omit to wait indefinitely (rarely wise —
   * pass a budget aligned with the turn timeout). */
  timeoutMs?: number;
  /** Optional engine-side deadline (ISO-8601) recorded on the request. */
  expiresAt?: string;
}

/** Raised by {@link ApprovalCoordinator.requestApproval} when no decision
 * arrives within `timeoutMs`. */
export class ApprovalTimeoutError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ApprovalTimeoutError";
  }
}

const noopLogger: Logger = {
  debug: () => {},
  info: () => {},
  warn: () => {},
  error: () => {},
};

/** Collaborators the coordinator needs, injected by the supervisor. */
export interface ApprovalCoordinatorOptions {
  /** Legacy envelope sink — used when a request has no `executionId`. */
  send?: (frame: Envelope) => Promise<void>;
  /** Unified execution-frame sender — used when a request carries an
   *  `executionId`. One of `send` or `sender` must be provided. */
  sender?: ExecutionFrameSender;
  logger?: Logger;
  /** Override the client-request-id generator (tests). Defaults to a UUID. */
  generateId?: () => string;
}

interface PendingApproval {
  resolve: (decision: ApprovalDecisionWire) => void;
  reject: (err: Error) => void;
  timer?: ReturnType<typeof setTimeout>;
}

export class ApprovalCoordinator {
  private readonly legacySend?: (frame: Envelope) => Promise<void>;
  private readonly sender?: ExecutionFrameSender;
  private readonly log: Logger;
  private readonly generateId: () => string;
  private readonly pending = new Map<string, PendingApproval>();

  constructor(options: ApprovalCoordinatorOptions) {
    if (!options.send && !options.sender) {
      throw new Error("ApprovalCoordinator requires either send or sender");
    }
    this.legacySend = options.send;
    this.sender = options.sender;
    this.log = options.logger ?? noopLogger;
    this.generateId = options.generateId ?? randomUUID;
  }

  /**
   * Emit an approval request and block until the decision returns.
   *
   * When `options.executionId` is present, sends a durable
   * `execution.approval.requested` frame via the unified sender; otherwise
   * falls back to the legacy `approval.request` envelope for the
   * conversation path (deleted in Task 6).
   */
  async requestApproval(options: RequestApprovalOptions): Promise<ApprovalOutcome> {
    const clientRequestId = this.generateId();
    const executionId = options.executionId;
    const unified = executionId !== undefined && this.sender !== undefined;

    const body: Record<string, unknown> = { ...(options.payload ?? {}) };
    if (body.clientRequestId === undefined) {
      body.clientRequestId = clientRequestId;
    }

    const decisionPromise = new Promise<ApprovalDecisionWire>(
      (resolve, reject) => {
        const entry: PendingApproval = { resolve, reject };
        if (options.timeoutMs !== undefined) {
          entry.timer = setTimeout(() => {
            this.pending.delete(clientRequestId);
            reject(
              new ApprovalTimeoutError(
                `No approval decision for ${unified ? executionId : options.conversationId} ` +
                  `(clientRequestId ${clientRequestId}) within ${options.timeoutMs}ms`,
              ),
            );
          }, options.timeoutMs);
        }
        this.pending.set(clientRequestId, entry);
      },
    );

    try {
      if (unified) {
        const summary = options.content ?? "Approval requested";
        const payload: ExecutionApprovalRequestedPayload = {
          executionId,
          dispatchId: executionId,
          approvalRequestId: clientRequestId,
          action: {
            actionId: clientRequestId,
            type: "TOOL_EXECUTION",
            riskClass: (options.payload?.riskClass as string) ?? "DESTRUCTIVE",
            summary,
            digest: safeStringify(body),
          },
          snapshotTreeHash: null,
          stateDigest: null,
          expiresAt: options.expiresAt ?? new Date(Date.now() + 300_000).toISOString(),
        };
        await this.sender!.sendExecutionApprovalRequested(payload);
      } else {
        await this.legacySend!(
          approvalRequest({
            conversationId: options.conversationId ?? "unknown",
            clientRequestId,
            ...(options.content !== undefined ? { content: options.content } : {}),
            payloadJson: safeStringify(body),
            ...(options.expiresAt !== undefined
              ? { expiresAt: options.expiresAt }
              : {}),
          }),
        );
      }
    } catch (err) {
      // Could not even send the request — clean up the pending wait.
      const entry = this.pending.get(clientRequestId);
      if (entry?.timer) {
        clearTimeout(entry.timer);
      }
      this.pending.delete(clientRequestId);
      throw err instanceof Error ? err : new Error(String(err));
    }

    this.log.info(
      `Requested approval for ${unified ? executionId : options.conversationId} (clientRequestId ${clientRequestId})`,
    );

    const decision = await decisionPromise;
    return toOutcome(decision);
  }

  /**
   * Resolve a pending approval from an inbound `approval.decision`. Returns
   * `true` if a matching wait was resolved, `false` for an unknown or
   * already-settled id (treated as a benign late delivery).
   */
  resolve(decision: ApprovalDecisionWire): boolean {
    const clientRequestId = decision.clientRequestId;
    if (!clientRequestId) {
      this.log.warn(
        `approval.decision without clientRequestId (conversation ${decision.conversationId}) — cannot route`,
      );
      return false;
    }
    const entry = this.pending.get(clientRequestId);
    if (!entry) {
      this.log.debug(
        `approval.decision for unknown clientRequestId ${clientRequestId} — likely a late delivery`,
      );
      return false;
    }
    if (entry.timer) {
      clearTimeout(entry.timer);
    }
    this.pending.delete(clientRequestId);
    entry.resolve(decision);
    return true;
  }

  /** Number of in-flight approval requests awaiting a decision. */
  get pendingCount(): number {
    return this.pending.size;
  }
}

/** Map a wire decision to the friendlier {@link ApprovalOutcome}. */
function toOutcome(decision: ApprovalDecisionWire): ApprovalOutcome {
  const verdict = decision.decision;
  return {
    decision: verdict,
    approved: verdict === "APPROVED",
    rejected: verdict === "REJECTED",
    expired: verdict === "EXPIRED",
    ...(decision.comment != null ? { comment: decision.comment } : {}),
    ...(decision.approverUserId != null
      ? { approverUserId: decision.approverUserId }
      : {}),
    ...(decision.requestMessageId != null
      ? { requestMessageId: decision.requestMessageId }
      : {}),
    ...(decision.responseMessageId != null
      ? { responseMessageId: decision.responseMessageId }
      : {}),
  };
}

/** Defensive JSON serialisation so a non-serialisable value in `payload`
 * cannot break the wire format mid-turn. */
function safeStringify(obj: Record<string, unknown>): string {
  try {
    return JSON.stringify(obj);
  } catch {
    return JSON.stringify({ raw: String(obj) });
  }
}
