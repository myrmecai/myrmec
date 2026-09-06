// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Local durable outbox for Agent orchestration events and terminal results
 * (design §16.3, Feature 10). Records are keyed by event/result id —
 * duplicates are suppressed at enqueue. Delivery is at-least-once with
 * idempotent consumers engine-side; the sink retransmits unacknowledged
 * records after reconnect. When the outbox backend is unavailable (or the
 * socket is down), `checkHealthy` reports false so the runner STOPS before
 * the next governed side effect instead of silently dropping frames.
 */
import { mkdirSync, readdirSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import type { Envelope } from "../protocol/envelope.js";

export type OutboxRecordKind = "event" | "result" | "approval_requested";

export interface OutboxRecord {
  id: string;
  kind: OutboxRecordKind;
  /** The wire envelope payload (already validated + redacted). */
  payload: unknown;
  /** Strictly monotonic per-dispatch sequence for events. */
  sequence?: number;
  enqueuedAt: string;
  /** Null until the transport confirmed delivery of this record. */
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
          // A corrupt record is quarantined by ignoring it — the sender
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
  /** Sends one encoded envelope; resolves true when delivered. */
  send(envelope: Envelope): Promise<boolean>;
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
   * The governed-side-effect gate (§16.3): the caller MUST check this
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
   * Drain: send every unacknowledged record (retransmission after
   * reconnect), marking each acknowledged on confirmed delivery. A send
   * failure marks the outbox unhealthy and stops — the next drain resumes
   * from this record.
   */
  async drain(): Promise<number> {
    this.markHealthy();
    const records = await this.options.backend.list();
    let sent = 0;
    for (const record of records) {
      if (record.acknowledgedAt) continue;
      const delivered = await this.options.send(
        envelopeFor(record),
      );
      if (!delivered) {
        this.markUnhealthy("send failed during drain");
        break;
      }
      await this.options.backend.acknowledge(record.id);
      sent++;
    }
    return sent;
  }

  /** Acknowledge one specific record (engine confirmed idempotent ingest). */
  async acknowledge(id: string): Promise<void> {
    await this.options.backend.acknowledge(id);
  }
}

function envelopeFor(record: OutboxRecord): Envelope {
  const type =
    record.kind === "event"
      ? "orchestration.event"
      : record.kind === "result"
        ? "orchestration.result"
        : "orchestration.approval_requested";
  return { type, timestamp: new Date().toISOString(), payload: record.payload };
}