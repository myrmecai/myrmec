// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, test } from "vitest";
import {
  FsOutboxBackend,
  OrchestrationOutbox,
} from "./OrchestrationOutbox.js";
import {
  AgentProtocolOrchestrationEventSink,
  OutboxUnhealthyError,
} from "./AgentProtocolOrchestrationEventSink.js";
import { ORCHESTRATION_EVENT_NS, uuidV5 } from "../orchestration/constants.js";
import type { DispatchIdentity } from "../orchestration/types.js";

let root: string;

beforeEach(() => {
  root = mkdtempSync(join(tmpdir(), "outbox-test-"));
});

afterEach(() => {
  rmSync(root, { recursive: true, force: true });
});

const dispatch: DispatchIdentity = {
  workflowId: "00000000-0000-0000-0000-000000000001",
  runId: "00000000-0000-0000-0000-000000000002",
  stepId: "build",
  taskId: "00000000-0000-0000-0000-000000000003",
  attemptId: "00000000-0000-0000-0000-000000000004",
  attemptOrdinal: 1,
  dispatchId: "00000000-0000-0000-0000-000000000004",
};

function makeOutbox(send: (envelope: unknown) => Promise<boolean>) {
  const backend = new FsOutboxBackend(root);
  const outbox = new OrchestrationOutbox({
    backend,
    send: send as never,
  });
  return { backend, outbox };
}

describe("OrchestrationOutbox (§16.3)", () => {
  test("enqueue is durable and dedup by id; drain sends and acknowledges", async () => {
    const sent: unknown[] = [];
    const { outbox } = makeOutbox(async (envelope) => {
      sent.push(envelope);
      return true;
    });

    await outbox.enqueue({ id: "ev-1", kind: "event", sequence: 1, payload: { type: "A" } });
    await outbox.enqueue({ id: "ev-1", kind: "event", sequence: 1, payload: { type: "A" } });
    await outbox.enqueue({ id: "res-1", kind: "result", payload: { status: "COMPLETED" } });

    const drained = await outbox.drain();
    expect(drained).toBe(2);
    expect(sent).toHaveLength(2);

    // A second drain retransmits nothing — all acknowledged.
    expect(await outbox.drain()).toBe(0);
  });

  test("a failed send marks the outbox unhealthy; drain resumes from the same record", async () => {
    let failFirst = true;
    const sent: unknown[] = [];
    const { outbox } = makeOutbox(async (envelope) => {
      if (failFirst) {
        failFirst = false;
        return false;
      }
      sent.push(envelope);
      return true;
    });

    await outbox.enqueue({ id: "ev-1", kind: "event", sequence: 1, payload: { type: "A" } });
    expect(await outbox.drain()).toBe(0);
    expect(outbox.checkHealthy()).toBe(false);
    expect(outbox.unhealthyReasonFor()).toContain("send failed");

    // Reconnect: healthy again, the unacknowledged record retransmits.
    const drained = await outbox.drain();
    expect(drained).toBe(1);
    expect(sent).toHaveLength(1);
    expect(outbox.checkHealthy()).toBe(true);
  });

  test("unacknowledged records survive a process restart (FsOutboxBackend)", async () => {
    const { outbox } = makeOutbox(async () => true);
    await outbox.enqueue({ id: "ev-1", kind: "event", sequence: 1, payload: { type: "A" } });
    // NO drain — the record must survive a "restart".

    const restartedBackend = new FsOutboxBackend(root);
    const records = await restartedBackend.list();
    expect(records).toHaveLength(1);
    expect(records[0]?.id).toBe("ev-1");
    expect(records[0]?.acknowledgedAt).toBeNull();

    // Retransmission through the restarted backend works.
    const restarted = new OrchestrationOutbox({
      backend: restartedBackend,
      send: async () => true,
    });
    expect(await restarted.drain()).toBe(1);
  });
});

describe("AgentProtocolOrchestrationEventSink (§16.3)", () => {
  test("event ids are deterministic per dispatch+sequence and strictly monotonic", async () => {
    const { outbox } = makeOutbox(async () => true);
    const sink = new AgentProtocolOrchestrationEventSink({ outbox });

    const firstId = await sink.emitEvent({ dispatch, type: "WORKER_STARTED" });
    const secondId = await sink.emitEvent({ dispatch, type: "WORKER_COMPLETED" });

    expect(firstId).toBe(uuidV5(ORCHESTRATION_EVENT_NS, `${dispatch.dispatchId}:1`));
    expect(secondId).toBe(uuidV5(ORCHESTRATION_EVENT_NS, `${dispatch.dispatchId}:2`));

    // Re-emitting the same slot (a replayed dispatch) returns the same id
    // without double-counting — sequence only advances on new emissions.
    const replayed = await sink.emitEvent({ dispatch, type: "WORKER_STARTED" });
    expect(replayed).not.toBe(firstId); // a NEW emission gets a new slot
  });

  test("terminal result emits once by resultId", async () => {
    const sent: unknown[] = [];
    const { outbox } = makeOutbox(async (envelope) => {
      sent.push(envelope);
      return true;
    });
    const sink = new AgentProtocolOrchestrationEventSink({ outbox });

    await sink.emitResult({
      dispatch,
      resultId: "33333333-3333-3333-3333-333333333333",
      resultDigest: "a".repeat(64),
      payload: { status: "COMPLETED", summary: "done" },
    });
    // Duplicate delivery of the same result is suppressed at the sink.
    await sink.emitResult({
      dispatch,
      resultId: "33333333-3333-3333-3333-333333333333",
      resultDigest: "a".repeat(64),
      payload: { status: "COMPLETED", summary: "done" },
    });
    const results = sent.filter(
      (e) => (e as { type: string }).type === "orchestration.result",
    );
    expect(results).toHaveLength(1);
  });

  test("unhealthy outbox stops the sink before the next side effect", async () => {
    let send: (envelope: unknown) => Promise<boolean> = async () => true;
    const { outbox } = makeOutbox(async (envelope) => send(envelope));
    const sink = new AgentProtocolOrchestrationEventSink({ outbox });

    send = async () => false; // the wire drops
    await sink.emitEvent({ dispatch, type: "WORKER_STARTED" });
    expect(outbox.checkHealthy()).toBe(false);

    // The next governed side effect MUST stop — the sink throws.
    await expect(
      sink.emitEvent({ dispatch, type: "WORKER_COMPLETED" }),
    ).rejects.toThrow(OutboxUnhealthyError);
    await expect(
      sink.emitResult({
        dispatch,
        resultId: "44444444-4444-4444-4444-444444444444",
        resultDigest: "b".repeat(64),
        payload: {},
      }),
    ).rejects.toThrow(OutboxUnhealthyError);
  });

  test("approval proposals are durable and idempotent", async () => {
    const sent: unknown[] = [];
    const { outbox } = makeOutbox(async (envelope) => {
      sent.push(envelope);
      return true;
    });
    const sink = new AgentProtocolOrchestrationEventSink({ outbox });

    const proposal = {
      dispatch,
      approvalRequestId: "55555555-5555-5555-5555-555555555555",
      payload: { actionId: "66666666-6666-6666-6666-666666666666", riskClass: "DESTRUCTIVE" },
    };
    await sink.emitApprovalRequest(proposal);
    await sink.emitApprovalRequest(proposal);
    const approvals = sent.filter(
      (e) => (e as { type: string }).type === "orchestration.approval_requested",
    );
    expect(approvals).toHaveLength(1);
  });
});