// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * The OrchestrationRunner's event/result/approval sink routed through the
 * durable outbox (design §16.3, Feature 10). Every governed side effect's
 * evidence first lands durably in the outbox, THEN goes to the wire; when
 * the outbox or socket is unhealthy the sink stops accepting new records
 * and the runner must stop before the next governed side effect.
 *
 * Event IDs and per-envelope sequences are deterministic and
 * dispatch-local (§16.3): `eventId = uuidV5(EVENT_NS, "<dispatchId>:<sequence>")`
 * — DispatchIdentity carries no sequence of its own.
 */
import type { OrchestrationOutbox } from "./OrchestrationOutbox.js";
import { ORCHESTRATION_EVENT_NS, uuidV5 } from "../orchestration/constants.js";
import type { DispatchIdentity } from "../orchestration/types.js";
import { CaptureFilter } from "../executor/CaptureFilter.js";
import type { CapturePolicy } from "./unifiedFrames.js";
import type { Logger } from "../models/index.js";

export interface AgentProtocolOrchestrationEventSinkOptions {
  outbox: OrchestrationOutbox;
  /**
   * §8.4/§15 rule 12: the session's capture policy the dispatch rides.
   * Absent/null ⇒ METADATA (fail closed) — the orchestration progress
   * stream is metadata by construction, and an unknown policy must not
   * widen what the sink emits. maxBytes truncation applies regardless.
   */
  capturePolicy?: CapturePolicy | null;
  logger?: Logger;
}

export interface OrchestrationProgressEvent {
  dispatch: DispatchIdentity;
  type: string;
  occurredAt?: string;
  status?: string;
  workerName?: string;
  callId?: string;
  candidateTreeHash?: string;
  durationMs?: number;
  usage?: { workerCalls: number; rejectionCount: number; totalTokens: number };
  /** ┬º8.4 metadata columns (ORCHESTRATION_FUNCTION_* / VERIFICATION / CHECKPOINT / PROGRESS). */
  outcome?: string;
  functionName?: string;
  modelCode?: string;
  purpose?: string;
  verifierName?: string;
  verdict?: string;
  commitHash?: string;
  treeHash?: string;
  changedFileCount?: number;
  progressMessage?: string;
  percentage?: number;
}

export interface TerminalResult {
  dispatch: DispatchIdentity;
  resultId: string;
  resultDigest: string;
  payload: Record<string, unknown>;
}

export interface ApprovalProposal {
  dispatch: DispatchIdentity;
  approvalRequestId: string;
  payload: Record<string, unknown>;
}

/**
 * The sink. Not a constructor per dispatch — a supervisor holds one sink
 * per outbox and passes the dispatch identity per record.
 */
export class AgentProtocolOrchestrationEventSink {
  private readonly sequences = new Map<string, number>();
  private readonly emitted = new Set<string>();
  /** §15 rule 12: the session's capture filter (null policy ⇒ METADATA). */
  private captureFilter: CaptureFilter;

  constructor(private readonly options: AgentProtocolOrchestrationEventSinkOptions) {
    this.captureFilter = new CaptureFilter(options.capturePolicy ?? null, options.logger);
  }

  /**
   * §7.3/§15 rule 12: bind the executing dispatch's session capture policy
   * before the run's side effects begin (a dispatch admitted from a session
   * with a capture block filters its progress stream with it). Called by
   * the executor at dispatch start; one sink serves the serialized
   * execution chain (§17.6: one dispatch at a time), so the binding is
   * unambiguous.
   */
  bindCapturePolicy(capture: CapturePolicy | null): void {
    this.captureFilter = new CaptureFilter(capture ?? null, this.options.logger);
  }

  /** Deterministic dispatch-local event id for a sequence slot. */
  eventIdFor(dispatchId: string, sequence: number): string {
    return uuidV5(ORCHESTRATION_EVENT_NS, `${dispatchId}:${sequence}`);
  }

  /**
   * Emit one progress event: assigns the next strictly-monotonic
   * dispatch-local sequence, derives the deterministic eventId, persists
   * the record durably, then sends. Throws OutboxUnhealthyError when the
   * outbox is down — the runner stops before the next side effect.
   *
   * §8.4/§15 rule 12: the built payload passes the session's CaptureFilter
   * (event type → allowlist; conservative fallback for the orchestration
   * progress types) and the maxBytes budget before it is enqueued.
   */
  async emitEvent(event: OrchestrationProgressEvent): Promise<string> {
    this.assertHealthy();
    const dispatchId = event.dispatch.dispatchId;
    const sequence = this.nextSequence(dispatchId);
    const eventId = this.eventIdFor(dispatchId, sequence);

    // Idempotent re-emission of the same slot returns the same id —
    // a replayed dispatch never double-counts.
    if (this.emitted.has(eventId)) return eventId;
    this.emitted.add(eventId);

    // §15 rule 12: filter + truncate BEFORE the durable record exists —
    // sensitive content must not land in the outbox either.
    const filter = this.captureFilter;
    const raw: Record<string, unknown> = {
      ...(event.status !== undefined ? { status: event.status } : {}),
      ...(event.workerName !== undefined ? { workerName: event.workerName } : {}),
      ...(event.callId !== undefined ? { callId: event.callId } : {}),
      ...(event.candidateTreeHash !== undefined
        ? { candidateTreeHash: event.candidateTreeHash }
        : {}),
      ...(event.durationMs !== undefined ? { durationMs: event.durationMs } : {}),
      ...(event.usage !== undefined ? { usage: event.usage } : {}),
      // ┬º8.4 metadata columns (the sensitive ladder strips anything else).
      ...(event.outcome !== undefined ? { outcome: event.outcome } : {}),
      ...(event.functionName !== undefined ? { functionName: event.functionName } : {}),
      ...(event.modelCode !== undefined ? { modelCode: event.modelCode } : {}),
      ...(event.purpose !== undefined ? { purpose: event.purpose } : {}),
      ...(event.verifierName !== undefined ? { verifierName: event.verifierName } : {}),
      ...(event.verdict !== undefined ? { verdict: event.verdict } : {}),
      ...(event.commitHash !== undefined ? { commitHash: event.commitHash } : {}),
      ...(event.treeHash !== undefined ? { treeHash: event.treeHash } : {}),
      ...(event.changedFileCount !== undefined
        ? { changedFileCount: event.changedFileCount }
        : {}),
      ...(event.progressMessage !== undefined
        ? { progressMessage: event.progressMessage }
        : {}),
      ...(event.percentage !== undefined ? { percentage: event.percentage } : {}),
    };
    const filtered =
      filter.level === "METADATA"
        ? filter.filterEvent(event.type, raw)
        : raw;
    // Budget the WHOLE payload: the envelope's fixed keys (schemaVersion,
    // eventId, dispatch, type, sequence, occurredAt) are unavoidable overhead —
    // only the remainder of maxBytes is available to the data map.
    const fixedPayload = {
      schemaVersion: "1.0",
      eventId,
      dispatch: event.dispatch,
      type: event.type,
      sequence,
      occurredAt: event.occurredAt ?? new Date().toISOString(),
    };
    const overheadBytes = CaptureFilter.encodedByteLength({
      ...fixedPayload,
      ...CaptureFilter.EMPTY_PLACEHOLDER,
    });
    const dataBudget =
      filter.maxBytes === null
        ? null
        : Math.max(0, filter.maxBytes - overheadBytes);
    const safeData =
      dataBudget === null
        ? filter.truncate(filtered, event.type)
        : filter.truncate(filtered, event.type, dataBudget);

    await this.options.outbox.enqueue({
      id: eventId,
      kind: "event",
      sequence,
      payload: {
        ...fixedPayload,
        ...safeData,
      },
    });
    await this.options.outbox.drain();
    return eventId;
  }

  /**
   * Emit the ONE logical terminal result. Keyed by resultId — duplicate
   * delivery is allowed and the engine dedups; enqueue is idempotent.
   */
  async emitResult(result: TerminalResult): Promise<void> {
    this.assertHealthy();
    if (this.emitted.has(result.resultId)) return;
    this.emitted.add(result.resultId);
    await this.options.outbox.enqueue({
      id: result.resultId,
      kind: "result",
      payload: {
        schemaVersion: "1.0",
        ...result.payload,
        resultId: result.resultId,
        resultDigest: result.resultDigest,
        dispatch: result.dispatch,
      },
    });
    await this.options.outbox.drain();
  }

  /** Emit one durable HITL proposal (approval_requested). */
  async emitApprovalRequest(proposal: ApprovalProposal): Promise<void> {
    this.assertHealthy();
    const key = `approval:${proposal.approvalRequestId}`;
    if (this.emitted.has(key)) return;
    this.emitted.add(key);
    await this.options.outbox.enqueue({
      id: proposal.approvalRequestId,
      kind: "approval_requested",
      payload: {
        schemaVersion: "1.0",
        ...proposal.payload,
        approvalRequestId: proposal.approvalRequestId,
        dispatch: proposal.dispatch,
      },
    });
    await this.options.outbox.drain();
  }

  /** The drain entry point after a reconnect (§16.3 retransmission). */
  async retransmitUnacknowledged(): Promise<number> {
    return this.options.outbox.drain();
  }

  private nextSequence(dispatchId: string): number {
    const next = (this.sequences.get(dispatchId) ?? 0) + 1;
    this.sequences.set(dispatchId, next);
    return next;
  }

  private assertHealthy(): void {
    if (!this.options.outbox.checkHealthy()) {
      throw new OutboxUnhealthyError(
        this.options.outbox.unhealthyReasonFor() ?? "outbox unhealthy",
      );
    }
  }
}

/** The stop-before-side-effect signal (§16.3). */
export class OutboxUnhealthyError extends Error {
  constructor(reason: string) {
    super(`orchestration outbox unhealthy — stopping before the next side effect: ${reason}`);
    this.name = "OutboxUnhealthyError";
  }
}