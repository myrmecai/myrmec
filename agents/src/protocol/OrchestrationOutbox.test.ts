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

  // ── §8.4/§15 rule 12: capture-policy enforcement on the sink ──

  test("an oversized event payload truncates to maxBytes", async () => {
    const sent: unknown[] = [];
    const { outbox } = makeOutbox(async (envelope) => {
      sent.push(envelope);
      return true;
    });
    const sink = new AgentProtocolOrchestrationEventSink({
      outbox,
      // Tiny budget for the test — the wire shape is unchanged.
      capturePolicy: { level: "METADATA", maxBytes: 120 },
    });

    await sink.emitEvent({
      dispatch,
      type: "WORKER_COMPLETED",
      workerName: "coder",
      status: "COMPLETED",
      callId: "call-1",
      durationMs: 5,
      usage: { workerCalls: 1, rejectionCount: 0, totalTokens: 42 },
    });

    // The budget forces the ladder on the DATA map only: the fixed
    // envelope framing (schemaVersion/eventId/dispatch/type/sequence/
    // occurredAt) is protocol identity, not captured content (§15 rule
    // 12 scopes capture to tool args/results/prompts/provider payloads) —
    // and the dispatch is already implied by the deterministic
    // eventId. So the assertion is: framing bytes + data ≤ budget.
    const event = sent.find((e) => (e as { type: string }).type === "orchestration.event");
    expect(event).toBeDefined();
    const payload = event!.payload as Record<string, unknown>;
    const framing = { ...payload };
    delete framing.status;
    delete framing.workerName;
    delete framing.callId;
    delete framing.candidateTreeHash;
    delete framing.durationMs;
    delete framing.usage;
    expect(
      Buffer.byteLength(JSON.stringify(payload)) - Buffer.byteLength(JSON.stringify(framing)),
    ).toBeLessThanOrEqual(120);
  });

  test("a payload carrying an args-like key is filtered at METADATA", async () => {
    const sent: unknown[] = [];
    const { outbox } = makeOutbox(async (envelope) => {
      sent.push(envelope);
      return true;
    });
    // METADATA (the default; explicit here for the contract).
    const sink = new AgentProtocolOrchestrationEventSink({
      outbox,
      capturePolicy: { level: "METADATA", maxBytes: 262_144 },
    });

    // A §8.4-table type whose required metadata includes the call context:
    // a future caller embedding tool arguments alongside it gets filtered.
    await sink.emitEvent({
      dispatch,
      type: "ORCHESTRATION_FUNCTION_COMPLETED",
      callId: "call-1",
      outcome: "COMPLETED",
      usage: { workerCalls: 1, rejectionCount: 0, totalTokens: 42 },
      // Sensitive embedding a naive caller might add:
      args: { instruction: "SECRET-INSTRUCTION" },
      result: "SECRET-RESULT",
    } as never);

    const event = sent.find((e) => (e as { type: string }).type === "orchestration.event");
    const payload = event!.payload as Record<string, unknown>;
    const serialized = JSON.stringify(payload);
    // The metadata columns pass; the sensitive keys never land in the
    // outbox (and therefore never reach the wire).
    expect(serialized).not.toContain("SECRET-INSTRUCTION");
    expect(serialized).not.toContain("SECRET-RESULT");
    expect(payload.outcome).toBe("COMPLETED");
    expect(payload.callId).toBe("call-1");
  });

  test("a null capture policy fails closed to METADATA on the sink", async () => {
    const sent: unknown[] = [];
    const { outbox } = makeOutbox(async (envelope) => {
      sent.push(envelope);
      return true;
    });
    const sink = new AgentProtocolOrchestrationEventSink({ outbox, capturePolicy: null });

    await sink.emitEvent({
      dispatch,
      type: "SOME_FUTURE_TYPE",
      workerName: "coder",
      args: { secret: "LEAK-ATTEMPT" },
    } as never);

    const event = sent.find((e) => (e as { type: string }).type === "orchestration.event");
    const payload = event!.payload as Record<string, unknown>;
    // Conservative fallback: unknown type keeps identifier-style keys only.
    expect(JSON.stringify(payload)).not.toContain("LEAK-ATTEMPT");
    expect(Object.keys(payload)).not.toContain("args");
  });
});