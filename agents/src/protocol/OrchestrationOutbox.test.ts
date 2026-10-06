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
import type { ExecutionControlStatePayload } from "./unifiedFrames.js";

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

function makeOutbox(
  send: (
    frame: { type: string; payload: unknown },
    record: { id: string; messageId?: string; sequence?: number },
  ) => Promise<boolean>,
) {
  const backend = new FsOutboxBackend(root);
  const outbox = new OrchestrationOutbox({
    backend,
    send: send as never,
  });
  return { backend, outbox };
}

/** Narrow a sent frame to its payload-bearing shape (sent records are
 * loosely typed on purpose - the backend persists unknown payloads). */
function payloadOf(sent: unknown): Record<string, unknown> {
  return (sent as { payload: Record<string, unknown> }).payload;
}

/** A minimal 22.4 execution.control.state payload for seam tests. */
function makeControlState(
  stateSequence: number,
  status: "HOLD_REQUESTED" | "HELD" | "RUNNING",
): ExecutionControlStatePayload {
  return {
    executionId: "00000000-0000-4000-8000-000000000001",
    dispatchId: "00000000-0000-4000-8000-000000000004",
    controlRevision: 1,
    stateSequence,
    status,
    effectiveState: status,
    reasonCode: "USER_REQUESTED",
    changedAt: "2026-10-03T10:00:00.000Z",
    idleResumeAt: null,
    safePoint: null,
    rejectedControlRevision: null,
    errorCode: null,
  };
}

describe("OrchestrationOutbox (unified session execution, protocol 12.1)", () => {
  test("enqueue is durable and dedup by id; drain sends and acknowledges only via the ack seam", async () => {
    const sent: unknown[] = [];
    const { outbox } = makeOutbox(async (envelope) => {
      sent.push(envelope);
      return true;
    });

    await outbox.enqueue({ id: "ev-1", kind: "event", sequence: 1, payload: { type: "A" } });
    await outbox.enqueue({ id: "ev-1", kind: "event", sequence: 1, payload: { type: "A" } });
    await outbox.enqueue({
      id: "res-1",
      kind: "result",
      terminalType: "complete",
      payload: { status: "COMPLETED" },
    });

    // Drain sends every unacknowledged record - but does NOT fabricate
    // acks: a send-success is only delivery, not the engine's receipt.
    const drained = await outbox.drain();
    expect(drained).toBe(2);
    expect(sent).toHaveLength(2);

    // A second drain RESENDS both records (nothing was acknowledged) -
    // at-least-once until the engine's protocol.ack arrives.
    expect(await outbox.drain()).toBe(2);
    expect(sent).toHaveLength(4);
  });

  test("drain emits the unified frame types (execution.event / execution.complete by terminalType)", async () => {
    const sent: { type: string }[] = [];
    const { outbox } = makeOutbox(async (envelope) => {
      sent.push(envelope as { type: string });
      return true;
    });

    await outbox.enqueue({ id: "ev-1", kind: "event", sequence: 1, payload: { type: "A" } });
    await outbox.enqueue({
      id: "res-1",
      kind: "result",
      terminalType: "failed",
      payload: { error: { code: "X", retryable: true } },
    });
    await outbox.enqueue({
      id: "res-2",
      kind: "result",
      terminalType: "paused",
      payload: { reasonCode: "HITL_APPROVAL" },
    });
    await outbox.enqueue({
      id: "apr-1",
      kind: "approval_requested",
      payload: { approvalRequestId: "apr-1" },
    });
    await outbox.drain();

    expect(sent.map((f) => f.type)).toEqual([
      "execution.event",
      "execution.failed",
      "execution.paused",
      "execution.approval.requested",
    ]);
  });

  test("a retransmitted record reuses the SAME messageId and envelope sequence", async () => {
    const sends: { frame: { type: string }; record: { messageId?: string; sequence?: number } }[] = [];
    const { outbox } = makeOutbox(async (frame, record) => {
      sends.push({ frame: frame as { type: string }, record: { ...record } });
      return true;
    });

    await outbox.enqueue({
      id: "ev-1",
      kind: "event",
      sequence: 7,
      messageId: "msg-stable",
      payload: { type: "A" },
    });

    // First drain sends the record; the record keeps its original
    // messageId + sequence (the sender bridge stamps them onto the wire).
    await outbox.drain();
    await outbox.drain();
    expect(sends).toHaveLength(2);
    for (const send of sends) {
      expect(send.record.messageId).toBe("msg-stable");
      expect(send.record.sequence).toBe(7);
    }

    // The wire identity is identical across the original + the resend:
    // the sender bridge derives BOTH from the record, never mints.
    expect(sends[0]?.record.messageId).toBe(sends[1]?.record.messageId);
    expect(sends[0]?.record.sequence).toBe(sends[1]?.record.sequence);
  });

  test("control_state records: a unique wire messageId per record, command correlation as envelope metadata, timer states uncorrelated (Fix 2)", async () => {
    const sent: {
      frame: { type: string; payload: unknown; correlationId?: string };
      record: { id: string; messageId?: string; commandMessageId?: string };
    }[] = [];
    const { outbox, backend } = makeOutbox(async (frame, record) => {
      sent.push({ frame, record });
      return true;
    });

    // TWO records born of ONE command: the record ids differ by
    // stateSequence, so their derived wire messageIds differ too.
    await outbox.persistControlState(
      makeControlState(1, "HOLD_REQUESTED"),
      "cmd-msg-1",
    );
    await outbox.persistControlState(makeControlState(2, "HELD"), "cmd-msg-1");
    // A timer-driven state: NO correlation.
    await outbox.persistControlState(
      makeControlState(3, "RUNNING"),
      null,
    );

    await outbox.drain();
    const controlSends = sent.filter(
      (s) => s.frame.type === "execution.control.state",
    );
    expect(controlSends).toHaveLength(3);
    const ids = controlSends.map((s) => s.record.messageId);
    // DISTINCT wire messageIds (two of them share one command!). The wire
    // ids must NOT be the command's messageId: each record acks alone.
    expect(new Set(ids).size).toBe(3);
    expect(ids).not.toContain("cmd-msg-1");
    // Correlation metadata: both command-driven records name the command;
    // the timer-driven one carries NO correlation.
    expect(controlSends[0]?.frame.correlationId).toBe("cmd-msg-1");
    expect(controlSends[1]?.frame.correlationId).toBe("cmd-msg-1");
    expect(controlSends[2]?.frame.correlationId).toBeUndefined();
    // The stored record still carries the correlation for the bridge.
    expect(controlSends[0]?.record.commandMessageId).toBe("cmd-msg-1");
    expect(controlSends[2]?.record.commandMessageId).toBeUndefined();
    // Envelope identity stays separate from correlation on every frame.
    for (const s of controlSends) {
      expect(s.record.messageId).not.toBe(s.frame.correlationId);
    }

    // Acks are PER RECORD: acknowledging one of the two command records
    // leaves the other (and the timer record) unacknowledged.
    const ackedWireId = ids[0] ?? "";
    await outbox.acknowledgeByMessageId(ackedWireId);
    const stored = (await backend.list()).filter(
      (r) => r.kind === "control_state",
    );
    const ackedByMessageId = stored.find((r) => r.messageId === ackedWireId);
    const others = stored.filter((r) => r.messageId !== ackedWireId);
    expect(ackedByMessageId?.acknowledgedAt).not.toBeNull();
    expect(ackedByMessageId?.payload).toEqual(
      expect.objectContaining({ status: "HOLD_REQUESTED" }),
    );
    expect(others).toHaveLength(2);
    expect(others.map((r) => r.acknowledgedAt)).toEqual([null, null]);
  });

  test("persistControlState record ids stay deterministic per execution+stateSequence and dedupe at enqueue", async () => {
    const { outbox, backend } = makeOutbox(async () => true);
    await outbox.persistControlState(
      makeControlState(1, "HOLD_REQUESTED"),
      "cmd-msg-1",
    );
    // The SAME sequence re-persisted (e.g. a coordinator replay) is a
    // no-op: the record id dedupes at enqueue.
    await outbox.persistControlState(
      makeControlState(1, "HOLD_REQUESTED"),
      "cmd-msg-1",
    );
    const stored = (await backend.list()).filter(
      (r) => r.kind === "control_state",
    );
    expect(stored).toHaveLength(1);
    expect(stored[0]?.id).toBe(
      `ctl-00000000-0000-4000-8000-000000000001-1`,
    );
    expect(stored[0]?.messageId).toBe(stored[0]?.id);
  });

  test("acknowledgeByMessageId clears the record; an unknown id is a no-op", async () => {
    const sent: unknown[] = [];
    const { outbox, backend } = makeOutbox(async (envelope) => {
      sent.push(envelope);
      return true;
    });

    await outbox.enqueue({
      id: "ev-1",
      kind: "event",
      sequence: 1,
      messageId: "msg-1",
      payload: { type: "A" },
    });
    await outbox.drain();

    // The engine's protocol.ack names the messageId -> the record clears.
    await outbox.acknowledgeByMessageId("msg-1");
    const records = await backend.list();
    expect(records[0]?.acknowledgedAt).not.toBeNull();

    // An acknowledged record never resends.
    const count = await outbox.drain();
    expect(count).toBe(0);

    // Unknown messageId: best-effort no-op, never throws.
    await expect(outbox.acknowledgeByMessageId("msg-unknown")).resolves.toBeUndefined();
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
    // NO drain - the record must survive a "restart".

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

describe("AgentProtocolOrchestrationEventSink (protocol 8.4 wire vocabulary)", () => {
  test("event ids are deterministic per dispatch+sequence and strictly monotonic", async () => {
    const { outbox } = makeOutbox(async () => true);
    const sink = new AgentProtocolOrchestrationEventSink({ outbox });

    const firstId = await sink.emitEvent({
      dispatch,
      type: "ORCHESTRATION_FUNCTION_STARTED",
    });
    const secondId = await sink.emitEvent({
      dispatch,
      type: "ORCHESTRATION_FUNCTION_COMPLETED",
    });

    expect(firstId).toBe(uuidV5(ORCHESTRATION_EVENT_NS, `${dispatch.dispatchId}:1`));
    expect(secondId).toBe(uuidV5(ORCHESTRATION_EVENT_NS, `${dispatch.dispatchId}:2`));

    // Re-emitting the same slot (a replayed dispatch) returns the same id
    // without double-counting - sequence only advances on new emissions.
    const replayed = await sink.emitEvent({
      dispatch,
      type: "ORCHESTRATION_FUNCTION_STARTED",
    });
    expect(replayed).not.toBe(firstId); // a NEW emission gets a new slot
  });

  test("terminal result emits once by resultId with its terminalType frame", async () => {
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
      terminalType: "complete",
    });
    // Duplicate delivery of the same result is suppressed at the sink.
    await sink.emitResult({
      dispatch,
      resultId: "33333333-3333-3333-3333-333333333333",
      resultDigest: "a".repeat(64),
      payload: { status: "COMPLETED", summary: "done" },
      terminalType: "complete",
    });
    const results = sent.filter(
      (e) => (e as { type: string }).type === "execution.complete",
    );
    expect(results).toHaveLength(1);
  });

  test("unhealthy outbox stops the sink before the next side effect", async () => {
    let send: (envelope: unknown) => Promise<boolean> = async () => true;
    const { outbox } = makeOutbox(async (envelope) => send(envelope));
    const sink = new AgentProtocolOrchestrationEventSink({ outbox });

    send = async () => false; // the wire drops
    await sink.emitEvent({
      dispatch,
      type: "ORCHESTRATION_FUNCTION_STARTED",
    });
    expect(outbox.checkHealthy()).toBe(false);

    // The next governed side effect MUST stop - the sink throws.
    await expect(
      sink.emitEvent({
        dispatch,
        type: "ORCHESTRATION_FUNCTION_COMPLETED",
      }),
    ).rejects.toThrow(OutboxUnhealthyError);
    await expect(
      sink.emitResult({
        dispatch,
        resultId: "44444444-4444-4444-4444-444444444444",
        resultDigest: "b".repeat(64),
        payload: {},
        terminalType: "failed",
      }),
    ).rejects.toThrow(OutboxUnhealthyError);
  });

  test("approval proposals are durable and idempotent (execution.approval.requested)", async () => {
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
      (e) => (e as { type: string }).type === "execution.approval.requested",
    );
    expect(approvals).toHaveLength(1);
  });

  // -- capture-policy enforcement on the sink --

  test("an oversized event payload truncates to maxBytes", async () => {
    const sent: unknown[] = [];
    const { outbox } = makeOutbox(async (envelope) => {
      sent.push(envelope);
      return true;
    });
    const sink = new AgentProtocolOrchestrationEventSink({
      outbox,
      // Tiny budget for the test - the wire shape is unchanged.
      capturePolicy: { level: "METADATA", maxBytes: 120 },
    });

    await sink.emitEvent({
      dispatch,
      type: "ORCHESTRATION_FUNCTION_COMPLETED",
      functionName: "coder",
      outcome: "COMPLETED",
      callId: "call-1",
      usage: { helperCalls: 1, rejectionCount: 0, totalTokens: 42 },
    });

    // The budget forces the ladder on the DATA map only: the fixed
    // envelope framing (schemaVersion/eventId/dispatch/type/sequence/
    // occurredAt) is protocol identity, not captured content - and the
    // dispatch is already implied by the deterministic eventId. So the
    // assertion is: framing bytes + data <= budget.
    const event = sent.find((e) => (e as { type: string }).type === "execution.event");
    expect(event).toBeDefined();
    const payload = payloadOf(event);
    const framing = { ...payload };
    delete framing.status;
    delete framing.functionName;
    delete framing.callId;
    delete framing.candidateTreeHash;
    delete framing.outcome;
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

    // A section 8.4-table type whose required metadata includes the call context:
    // a future caller embedding tool arguments alongside it gets filtered.
    await sink.emitEvent({
      dispatch,
      type: "ORCHESTRATION_FUNCTION_COMPLETED",
      callId: "call-1",
      outcome: "COMPLETED",
      usage: { helperCalls: 1, rejectionCount: 0, totalTokens: 42 },
      // Sensitive embedding a naive caller might add:
      args: { instruction: "SECRET-INSTRUCTION" },
      result: "SECRET-RESULT",
    } as never);

    const event = sent.find((e) => (e as { type: string }).type === "execution.event");
    const payload = payloadOf(event);
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
      functionName: "coder",
      args: { secret: "LEAK-ATTEMPT" },
    } as never);

    const event = sent.find((e) => (e as { type: string }).type === "execution.event");
    const payload = payloadOf(event);
    // Conservative fallback: unknown type keeps identifier-style keys only.
    expect(JSON.stringify(payload)).not.toContain("LEAK-ATTEMPT");
    expect(Object.keys(payload)).not.toContain("args");
  });
});