// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Local durable outbox for Agent orchestration events and terminal results
 * (unified session execution design, SDK touchpoint 5). Records are keyed
 * by event/result id - duplicates are suppressed at enqueue. Delivery is
 * at-least-once (protocol 12.1): a record is acknowledged ONLY when the
 * engine's `protocol.ack` names its messageId through
 * {@link OrchestrationOutbox.acknowledgeByMessageId} - `drain()` re-sends
 * and never fabricates acks. Retransmissions reuse BOTH the record's
 * `messageId` AND its `envelopeSequence` (a fresh value for either would
 * collide with the engine's dedup/slot-uniqueness as a conflicting
 * duplicate). When the outbox backend is unavailable (or the socket is
 * down), `checkHealthy` reports false so the runner STOPS before the next
 * governed side effect instead of silently dropping frames.
 */
import { mkdirSync, readdirSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import type {
  ExecutionControlStatePayload,
  ExecutionInteractionCompletePayload,
  ExecutionInteractionFailedPayload,
  ExecutionControlRequestPayload,
} from "./unifiedFrames.js";

export type OutboxRecordKind =
  | "event"
  | "result"
  | "approval_requested"
  /**
   * protocol 22.4 execution.control.state records (session interaction,
   * design 14.2 ownership table: "Durable controls state ... in the same
   * session sequence"). Additive kind - the coordinator persists every
   * published control state BEFORE the wire publish. The record's wire
   * `messageId` is unique per record (derived from the record id) so a
   * command's MULTIPLE records (HOLD_REQUESTED + a deferred HELD) each
   * get their own ack; the originating command's messageId rides the
   * `commandMessageId` field for envelope correlation. Timer-driven
   * states carry none (null).
   */
  | "control_state"
  /**
   * protocol 22.6 execution.interaction.complete records (Task 7: the
   * controller persists every settled outcome BEFORE the wire publish -
   * 22.3 "persist before send"). Record id `interaction-<id>-complete`
   * (Windows-filename-safe: no colons).
   */
  | "interaction_complete"
  /**
   * protocol 22.6 execution.interaction.failed records (Task 7) - the
   * failed outcome's durable record (same persist-before-send rule).
   */
  | "interaction_failed"
  /**
   * protocol 22.7 execution.control.request records (Task 7): the chat
   * tools' control proposals ride the SAME durable sequence. Record id
   * `cr-<controlRequestId>` (no colons).
   */
  | "control_request";

/**
 * The terminal frame subtype a `result` record carries (the record kind
 * splits by the runner outcome under the unified wire: execution.complete /
 * execution.failed / execution.paused).
 */
export type OutboxTerminalType = "complete" | "failed" | "paused";

export interface OutboxRecord {
  id: string;
  kind: OutboxRecordKind;
  /**
   * The wire envelope's messageId - stamped BEFORE the first send and
   * reused on every retransmission (protocol 12.1: a fresh id per resend
   * would defeat the engine's dedup). control_state records derive it
   * from their record id, so TWO records of ONE command carry DIFFERENT
   * wire messageIds (each is acked independently; 22.4 + 12.1).
   */
  messageId?: string;
  /**
   * control_state records only: the originating CONTROL command's
   * messageId (22.4 command correlation). Correlation metadata NOT a
   * wire identity: the sender bridge stamps it into the frame envelope's
   * `correlationId` (never `messageId`), and the engine acks each record
   * by its own unique frame messageId. Timer-driven states carry none.
   */
  commandMessageId?: string;
  /**
   * The terminal frame subtype for `kind: "result"` records
   * (execution.complete / execution.failed / execution.paused). Absent on
   * non-result kinds.
   */
  terminalType?: OutboxTerminalType;
  /** The wire envelope payload (already validated + redacted). */
  payload: unknown;
  /** Strictly monotonic per-dispatch sequence for events. */
  sequence?: number;
  /**
   * The per-session envelope sequence to stamp on retransmits (protocol
   * 12.1: the engine's slot uniqueness treats a fresh sequence as a
   * conflicting duplicate; retransmissions MUST reuse the original).
   */
  envelopeSequence?: number;
  enqueuedAt: string;
  /** Null until the engine's protocol.ack acknowledged this record. */
  acknowledgedAt: string | null;
}

export interface OutboxBackend {
  /** Persist one record durably (atomic temp-then-rename semantics). */
  put(record: OutboxRecord): Promise<void>;
  /** All records, unacknowledged first, then by sequence/enqueuedAt. */
  list(): Promise<OutboxRecord[]>;
  /** Mark one record acknowledged (idempotent). */
  acknowledge(id: string): Promise<void>;
  /** Drop acknowledged records beyond the retention window. */
  prune(olderThan: string): Promise<number>;
}

/** Filesystem backend: one JSON file per record under <root>/<kind>/. */
export class FsOutboxBackend implements OutboxBackend {
  private readonly ids = new Set<string>();

  constructor(private readonly root: string) {
    mkdirSync(join(root, "event"), { recursive: true });
    mkdirSync(join(root, "result"), { recursive: true });
    mkdirSync(join(root, "approval_requested"), { recursive: true });
    mkdirSync(join(root, "control_state"), { recursive: true });
    mkdirSync(join(root, "interaction_complete"), { recursive: true });
    mkdirSync(join(root, "interaction_failed"), { recursive: true });
    mkdirSync(join(root, "control_request"), { recursive: true });
    for (const record of this.scan()) this.ids.add(record.id);
  }

  async put(record: OutboxRecord): Promise<void> {
    if (this.ids.has(record.id)) return; // enqueue dedup by id
    this.ids.add(record.id);
    const dir = join(this.root, record.kind);
    const target = join(dir, `${record.id}.json`);
    const temp = join(dir, `.${record.id}.tmp`);
    writeFileSync(temp, JSON.stringify(record));
    renameSync(temp, target); // atomic publish
  }

  async list(): Promise<OutboxRecord[]> {
    return this.scan().sort(compareRecords);
  }

  async acknowledge(id: string): Promise<void> {
    const record = this.scan().find((r) => r.id === id);
    if (!record || record.acknowledgedAt) return;
    record.acknowledgedAt = new Date().toISOString();
    const target = join(this.root, record.kind, `${record.id}.json`);
    const temp = `${target}.tmp`;
    writeFileSync(temp, JSON.stringify(record));
    renameSync(temp, target);
  }

  async prune(olderThan: string): Promise<number> {
    let pruned = 0;
    for (const record of this.scan()) {
      if (record.acknowledgedAt && record.acknowledgedAt < olderThan) {
        rmSync(join(this.root, record.kind, `${record.id}.json`), { force: true });
        this.ids.delete(record.id);
        pruned++;
      }
    }
    return pruned;
  }

  private scan(): OutboxRecord[] {
    const records: OutboxRecord[] = [];
    for (const kind of [
      "event",
      "result",
      "approval_requested",
      "control_state",
      "interaction_complete",
      "interaction_failed",
      "control_request",
    ] as const) {
      const dir = join(this.root, kind);
      let names: string[];
      try {
        names = readdirSync(dir);
      } catch {
        continue;
      }
      for (const name of names) {
        if (!name.endsWith(".json")) continue;
        try {
          records.push(JSON.parse(readFileSync(join(dir, name), "utf-8")) as OutboxRecord);
        } catch {
          // A corrupt record is quarantined by ignoring it - the sender
          // still holds the in-memory copy and can re-enqueue.
        }
      }
    }
    return records;
  }
}

function compareRecords(a: OutboxRecord, b: OutboxRecord): number {
  const ackOrder = (a.acknowledgedAt ? 1 : 0) - (b.acknowledgedAt ? 1 : 0);
  if (ackOrder !== 0) return ackOrder;
  if (a.sequence !== undefined && b.sequence !== undefined && a.sequence !== b.sequence) {
    return a.sequence - b.sequence;
  }
  return a.enqueuedAt < b.enqueuedAt ? -1 : a.enqueuedAt > b.enqueuedAt ? 1 : 0;
}

export interface OrchestrationOutboxOptions {
  backend: OutboxBackend;
  /**
   * Sends one encoded frame derived from the record; resolves true when
   * delivered. Receives the record so the sender bridge can stamp the
   * frame's messageId and reuse the record's envelope sequence.
   */
  send(
    frame: { type: string; payload: unknown },
    record: OutboxRecord,
  ): Promise<boolean>;
}

export class OrchestrationOutbox {
  private readonly options: OrchestrationOutboxOptions;
  private healthy = true;

  constructor(options: OrchestrationOutboxOptions) {
    this.options = options;
  }

  /**
   * Enqueue one record durably BEFORE any side effect reports it: the
   * record must survive a crash between the event and the send. Duplicate
   * ids are no-ops (idempotent enqueue).
   */
  async enqueue(record: Omit<OutboxRecord, "enqueuedAt" | "acknowledgedAt">): Promise<void> {
    await this.options.backend.put({
      ...record,
      enqueuedAt: new Date().toISOString(),
      acknowledgedAt: null,
    });
  }

  /**
   * The governed-side-effect gate: the caller MUST check this
   * before the next side effect; when the outbox is unhealthy the runner
   * stops and retains the workspace instead of dropping frames.
   */
  checkHealthy(): boolean {
    return this.healthy;
  }

  markUnhealthy(reason: string): void {
    this.healthy = false;
    this.unhealthyReason = reason;
  }

  markHealthy(): void {
    this.healthy = true;
    this.unhealthyReason = null;
  }

  private unhealthyReason: string | null = null;
  unhealthyReasonFor(): string | null {
    return this.unhealthyReason;
  }

  /**
   * Drain: re-send every unacknowledged record (retransmission after
   * reconnect) REUSING the record's original messageId + envelope
   * sequence. A record is acknowledged ONLY via
   * {@link acknowledgeByMessageId} (the engine's protocol.ack) - this
   * method does NOT fabricate acks from send success (protocol 12.1
   * at-least-once). A send failure marks the outbox unhealthy and stops -
   * the next drain resumes from this record.
   */
  async drain(): Promise<number> {
    this.markHealthy();
    const records = await this.options.backend.list();
    let sent = 0;
    for (const record of records) {
      if (record.acknowledgedAt) continue;
      const delivered = await this.options.send(
        envelopeFor(record),
        record,
      );
      if (!delivered) {
        this.markUnhealthy("send failed during drain");
        break;
      }
      sent++;
    }
    return sent;
  }

  /**
   * Acknowledge the record whose outbound frame carried `messageId`
   * (protocol 12.1/12.3: the engine's protocol.ack names the frame it
   * durably recorded). Best-effort: an unknown id is a no-op (an ack for
   * a record this outbox never held, or one already pruned).
   */
  async acknowledgeByMessageId(messageId: string): Promise<void> {
    const records = await this.options.backend.list();
    const record = records.find((r) => r.messageId === messageId);
    if (!record) return;
    await this.options.backend.acknowledge(record.id);
  }

  /** Acknowledge one specific record by record id (engine confirmed idempotent ingest). */
  async acknowledge(id: string): Promise<void> {
    await this.options.backend.acknowledge(id);
  }

  /**
   * The session-interaction durable-emit seam (design 14.2 ownership
   * table): persist one protocol 22.4 execution.control.state payload
   * into the SAME session sequence as events/results. The record id is
   * derived deterministically from executionId + stateSequence
   * (retransmissions and coordinator replays of the same sequence dedupe
   * at enqueue). The record's wire `messageId` is UNIQUE PER RECORD -
   * derived from the record id (`ctl-<executionId>-<stateSequence>`):
   * one command can yield TWO control_state records (HOLD_REQUESTED now,
   * a deferred HELD at drain) and each frame must carry a distinct
   * messageId or `acknowledgeByMessageId` acks only the first and the
   * outbox retransmits the rest forever (12.1: the engine's protocol.ack
   * names one frame identity per record). The COMMAND correlation rides
   * the separate `commandMessageId` field - the sender bridge stamps it
   * into the frame envelope's `correlationId` (22.4: command-driven
   * states correlate to the originating command messageId; timer-driven
   * states have none - `commandMessageId` null).
   */
  async persistControlState(
    payload: ExecutionControlStatePayload,
    commandMessageId: string | null,
  ): Promise<void> {
    // The record id is Windows-filename-safe (no colons - the backend is
    // a filesystem): <prefix>-<executionId>-<stateSequence>.
    const id = `ctl-${payload.executionId}-${payload.stateSequence}`;
    await this.enqueue({
      id,
      kind: "control_state",
      // A UNIQUE wire identity per RECORD: the record id IS the frame
      // messageId (stable across retransmissions - 12.1 - and distinct
      // from every OTHER record, including those of the same command).
      messageId: id,
      commandMessageId: commandMessageId ?? undefined,
      payload,
    });
  }

  /**
   * The interaction-outcome durable seam (22.3 persist before send, 22.6):
   * the controller persists the settled COMPLETE outcome BEFORE the wire
   * publish. The record id (unique per interaction) is ALSO the frame
   * messageId - stable across retransmissions (12.1).
   */
  async persistInteractionComplete(
    payload: ExecutionInteractionCompletePayload,
  ): Promise<void> {
    const id = `interaction-${payload.interactionId}-complete`;
    await this.enqueue({
      id,
      kind: "interaction_complete",
      messageId: id,
      payload,
    });
  }

  /**
   * The interaction-failure durable seam (22.3/22.6): identical shape to
   * the complete seam for the FAILED outcome family (interaction failure
   * alone never fails the attempt, but the outcome is still durable).
   */
  async persistInteractionFailed(
    payload: ExecutionInteractionFailedPayload,
  ): Promise<void> {
    const id = `interaction-${payload.interactionId}-failed`;
    await this.enqueue({
      id,
      kind: "interaction_failed",
      messageId: id,
      payload,
    });
  }

  /**
   * The control-proposal durable seam (22.7): the request_* tools persist
   * their proposal BEFORE the wire emission. Record id `cr-<requestId>`
   * (no colons - Windows filesystem).
   */
  async persistControlRequest(
    payload: ExecutionControlRequestPayload,
  ): Promise<void> {
    const id = `cr-${payload.controlRequestId}`;
    await this.enqueue({
      id,
      kind: "control_request",
      messageId: id,
      payload,
    });
  }
}

/**
 * The unified frame each record emits on the wire (protocol 8.4/8.5/8.7/
 * 22.4/22.6/22.7): kind `event` -> execution.event; kind `result` ->
 * execution.<terminalType> (complete/failed/paused); kind
 * `approval_requested` -> execution.approval.requested; kind
 * `control_state` -> execution.control.state; kinds
 * `interaction_complete`/`interaction_failed` ->
 * execution.interaction.complete/failed; kind `control_request` ->
 * execution.control.request. For a control_state record carrying
 * `commandMessageId`, the frame's envelope-bound correlation is exposed
 * as metadata (the sender bridge stamps the envelope's `correlationId` -
 * correlation is metadata only, NEVER the frame's messageId identity;
 * protocol 22.4 + 12.1 ack contract). The legacy `orchestration.*`
 * envelope family is deleted (design D3).
 */
export interface OutboxFrameMetadata {
  /** The originating command's messageId - envelope correlation only. */
  correlationId?: string;
}

export function envelopeFor(record: OutboxRecord): {
  type: string;
  payload: unknown;
  correlationId?: string;
} {
  const type =
    record.kind === "event"
      ? "execution.event"
      : record.kind === "result"
        ? `execution.${record.terminalType ?? "complete"}`
        : record.kind === "control_state"
          ? "execution.control.state"
          : record.kind === "interaction_complete"
            ? "execution.interaction.complete"
            : record.kind === "interaction_failed"
              ? "execution.interaction.failed"
              : record.kind === "control_request"
                ? "execution.control.request"
                : "execution.approval.requested";
  const correlate =
    record.kind === "control_state" && record.commandMessageId !== undefined
      ? { correlationId: record.commandMessageId }
      : {};
  return { type, payload: record.payload, ...correlate };
}