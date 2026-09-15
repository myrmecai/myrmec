// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Human-in-the-loop approval coordinator (unified protocol, P6-T6).
 *
 * A handler's `requestApproval(...)` emits a durable
 * `execution.approval.requested` frame via the unified sender and blocks
 * until a decision returns. Under §8.6 the pause is terminal: a decided or
 * expired request resumes via the NEXT turn's `conversationContinuation`
 * (the host re-fetches decided requests from the engine rows), so this
 * coordinator's inbound resolve() covers the live-execution case while the
 * engine's durable rows are the recovery path. One coordinator is shared
 * by every in-flight execution; per-id waits keep concurrent requests
 * isolated (REQ-A-060/062).
 */
import { randomUUID } from "node:crypto";
import type { Logger } from "../models/index.js";
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
  /** Unified execution correlation id — required. */
  executionId: string;
  /** Short human-readable summary rendered on the approval card. */
  content?: string;
  /** Free-form metadata serialised to `action.summary`; the coordinator
   * always injects `clientRequestId` so the engine can correlate via the
   * persisted row even if the live frame is missed. */
  payload?: Record<string, unknown>;
  /** Milliseconds to await a decision before rejecting with
   * {@link ApprovalTimeoutError}. Omit to wait indefinitely (rarely wise —
   * pass a budget aligned with the execution timeout). */
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
  /** Unified execution-frame sender. Required. */
  sender: ExecutionFrameSender;
  logger?: Logger;
  /** Override the client-request-id generator (tests). Defaults to a UUID. */
  generateId?: () => string;
}

/** A decided approval as the executor routes it in (engine row shape). */
export interface ApprovalDecisionWire {
  conversationId?: string;
  clientRequestId?: string;
  decision: string;
  comment?: string | null;
  approverUserId?: string | null;
  requestMessageId?: string;
  responseMessageId?: string;
}

interface PendingApproval {
  resolve: (decision: ApprovalDecisionWire) => void;
  reject: (err: Error) => void;
  timer?: ReturnType<typeof setTimeout>;
}

export class ApprovalCoordinator {
  private readonly sender: ExecutionFrameSender;
  private readonly log: Logger;
  private readonly generateId: () => string;
  private readonly pending = new Map<string, PendingApproval>();

  constructor(options: ApprovalCoordinatorOptions) {
    if (!options.sender) {
      throw new Error("ApprovalCoordinator requires a sender");
    }
    this.sender = options.sender;
    this.log = options.logger ?? noopLogger;
    this.generateId = options.generateId ?? randomUUID;
  }

  /**
   * Emit `execution.approval.requested` and block until the decision
   * returns. When no decision arrives within `timeoutMs` the wait rejects
   * with {@link ApprovalTimeoutError}.
   */
  async requestApproval(options: RequestApprovalOptions): Promise<ApprovalOutcome> {
    const clientRequestId = this.generateId();
    const executionId = options.executionId;

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
                `No approval decision for execution ${executionId} ` +
                  `(clientRequestId ${clientRequestId}) within ${options.timeoutMs}ms`,
              ),
            );
          }, options.timeoutMs);
        }
        this.pending.set(clientRequestId, entry);
      },
    );

    try {
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
      await this.sender.sendExecutionApprovalRequested(payload);
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
      `Requested approval for execution ${executionId} (clientRequestId ${clientRequestId})`,
    );

    const decision = await decisionPromise;
    return toOutcome(decision);
  }

  /**
   * Resolve a pending approval from an inbound decision (engine row routed
   * by the host-control inbound, or re-fetched after resume). Returns
   * `true` if a matching wait was resolved, `false` for an unknown or
   * already-settled id (treated as a benign late delivery).
   */
  resolve(decision: ApprovalDecisionWire): boolean {
    const clientRequestId = decision.clientRequestId;
    if (!clientRequestId) {
      this.log.warn(
        `approval decision without clientRequestId — cannot route`,
      );
      return false;
    }
    const entry = this.pending.get(clientRequestId);
    if (!entry) {
      this.log.debug(
        `approval decision for unknown clientRequestId ${clientRequestId} — likely a late delivery`,
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
 * cannot break the wire format mid-execution. */
function safeStringify(obj: Record<string, unknown>): string {
  try {
    return JSON.stringify(obj);
  } catch {
    return JSON.stringify({ raw: String(obj) });
  }
}