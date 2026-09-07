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

export interface AgentProtocolOrchestrationEventSinkOptions {
  outbox: OrchestrationOutbox;
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

  constructor(private readonly options: AgentProtocolOrchestrationEventSinkOptions) {}

  /** Deterministic dispatch-local event id for a sequence slot. */
  eventIdFor(dispatchId: string, sequence: number): string {
    return uuidV5(ORCHESTRATION_EVENT_NS, `${dispatchId}:${sequence}`);
  }

  /**
   * Emit one progress event: assigns the next strictly-monotonic
   * dispatch-local sequence, derives the deterministic eventId, persists
   * the record durably, then sends. Throws OutboxUnhealthyError when the
   * outbox is down — the runner stops before the next side effect.
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

    await this.options.outbox.enqueue({
      id: eventId,
      kind: "event",
      sequence,
      payload: {
        schemaVersion: "1.0",
        eventId,
        dispatch: event.dispatch,
        type: event.type,
        sequence,
        occurredAt: event.occurredAt ?? new Date().toISOString(),
        ...(event.status !== undefined ? { status: event.status } : {}),
        ...(event.workerName !== undefined ? { workerName: event.workerName } : {}),
        ...(event.callId !== undefined ? { callId: event.callId } : {}),
        ...(event.candidateTreeHash !== undefined
          ? { candidateTreeHash: event.candidateTreeHash }
          : {}),
        ...(event.durationMs !== undefined ? { durationMs: event.durationMs } : {}),
        ...(event.usage !== undefined ? { usage: event.usage } : {}),
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