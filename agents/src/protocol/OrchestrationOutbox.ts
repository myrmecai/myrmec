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

export type OutboxRecordKind =
  | "event"
  | "result"
  | "approval_requested";

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
   * would defeat the engine's dedup).
   */
  messageId?: string;
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
    for (const kind of ["event", "result", "approval_requested"] as const) {
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
}

/**
 * The unified frame each record emits on the wire (protocol 8.4/8.5/8.7):
 * kind `event` -> execution.event; kind `result` -> execution.<terminalType>
 * (complete/failed/paused); kind `approval_requested` ->
 * execution.approval.requested. The legacy `orchestration.*` envelope
 * family is deleted (design D3).
 */
function envelopeFor(
  record: OutboxRecord,
): { type: string; payload: unknown } {
  const type =
    record.kind === "event"
      ? "execution.event"
      : record.kind === "result"
        ? `execution.${record.terminalType ?? "complete"}`
        : "execution.approval.requested";
  return { type, payload: record.payload };
}