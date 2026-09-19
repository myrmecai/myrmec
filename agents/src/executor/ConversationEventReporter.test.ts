// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * §8.4 producer tests: the ConversationEventReporter turns TurnExecutor
 * callbacks into capture-filtered execution.event frames on the sender.
 */
import { describe, it, expect } from "vitest";
import { ConversationEventReporter } from "./ConversationEventReporter.js";
import { CaptureFilter } from "./CaptureFilter.js";
import { executionEventPayloadSchema } from "../protocol/unifiedFrames.js";
import type { ExecutionEventPayload } from "../protocol/unifiedFrames.js";
import type { ExecutionFrameSender } from "./ExecutionFrameSender.js";
import type { ToolCallRecord } from "../models/index.js";

const EXECUTION_ID = "44444444-4444-4444-8444-444444444444";

/** Capture only the execution.event frames. */
function makeEventCapture(): {
  sender: ExecutionFrameSender;
  events: ExecutionEventPayload[];
} {
  const events: ExecutionEventPayload[] = [];
  const sender = {
    sendExecutionAccept: async () => {},
    sendExecutionReject: async () => {},
    sendExecutionDelta: async () => {},
    sendExecutionEvent: async (payload: ExecutionEventPayload) => {
      events.push(structuredClone(payload));
    },
    sendExecutionComplete: async () => {},
    sendExecutionFailed: async () => {},
    sendExecutionPaused: async () => {},
    sendExecutionCancelled: async () => {},
    sendExecutionCancel: async () => {},
    sendExecutionApprovalRequested: async () => {},
    sendProtocolError: async () => {},
  } satisfies ExecutionFrameSender;
  return { sender, events };
}

const EXECUTIONS: string[] = [];
let nextEvent = 0;
function makeReporter(
  sender: ExecutionFrameSender,
  policy: Parameters<typeof CaptureFilter>[0],
): ConversationEventReporter {
  EXECUTIONS.length = 0;
  const reporter = new ConversationEventReporter({
    sender,
    filter: new CaptureFilter(policy),
    generateEventId: () =>
      `55555555-${String(++nextEvent).padStart(4, "0")}-4111-8111-111111111111`,
  });
  // Observe the binding hooks so tests can assert the threading across
  // turns (the private binding stays the reporter's own).
  const begin = reporter.beginExecution.bind(reporter);
  reporter.beginExecution = (id: string) => {
    EXECUTIONS.push(id);
    begin(id);
  };
  return reporter;
}

function toolRecord(overrides: Partial<ToolCallRecord>): ToolCallRecord {
  return {
    toolCallId: "call-17",
    toolName: "read_file",
    args: { path: "/etc/passwd" },
    startedAt: 1_000,
    ...overrides,
  };
}

describe("ConversationEventReporter (§8.4)", () => {
  it("builds a capture-filtered TOOL_COMPLETED frame from onToolEnd", async () => {
    const { sender, events } = makeEventCapture();
    const reporter = makeReporter(sender, { level: "METADATA", maxBytes: 262_144 });
    reporter.beginExecution(EXECUTION_ID);

    await reporter.onToolEnd(
      toolRecord({ result: "root:x:0:0:...", completedAt: 1_042 }),
    );

    expect(events).toHaveLength(1);
    const payload = events[0]!;
    expect(payload.executionId).toBe(EXECUTION_ID);
    expect(payload.eventType).toBe("TOOL_COMPLETED");
    expect(payload.data).toEqual({
      toolName: "read_file",
      callId: "call-17",
      durationMs: 42,
      isError: false,
    });
    // §15 rule 12: the result content and the tool args never leaked.
    expect(CaptureFilter.sensitiveKeysRemain(payload.data)).toBe(false);
  });

  it("marks the error flag on a failed tool without leaking the error text", async () => {
    const { sender, events } = makeEventCapture();
    const reporter = makeReporter(sender, { level: "METADATA", maxBytes: null });
    reporter.beginExecution(EXECUTION_ID);

    await reporter.onToolEnd(
      toolRecord({ error: "EACCES: permission denied", completedAt: 1_010 }),
    );

    expect(events[0]!.data).toEqual({
      toolName: "read_file",
      callId: "call-17",
      durationMs: 10,
      isError: true,
    });
  });

  it("builds a TOOL_STARTED frame from onToolStart without the args", async () => {
    const { sender, events } = makeEventCapture();
    const reporter = makeReporter(sender, { level: "METADATA", maxBytes: null });
    reporter.beginExecution(EXECUTION_ID);

    await reporter.onToolStart(toolRecord({}));

    expect(events).toHaveLength(1);
    expect(events[0]!.eventType).toBe("TOOL_STARTED");
    expect(events[0]!.data).toEqual({ toolName: "read_file", callId: "call-17" });
  });

  it("threads the executionId across two turns (bind → emit → clear → rebind)", async () => {
    const { sender, events } = makeEventCapture();
    const reporter = makeReporter(sender, { level: "METADATA", maxBytes: null });

    const TURN_1 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    const TURN_2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

    reporter.beginExecution(TURN_1);
    await reporter.onProgress(10, 1);
    reporter.endExecution();

    reporter.beginExecution(TURN_2);
    await reporter.onProgress(20, 2);
    reporter.endExecution();

    expect(EXECUTIONS).toEqual([TURN_1, TURN_2]);
    expect(events.map((e) => e.executionId)).toEqual([TURN_1, TURN_2]);
    expect(events.map((e) => e.eventType)).toEqual(["PROGRESS", "PROGRESS"]);
    expect(events[0]!.data).toEqual({ message: "iteration 1", percentage: 10 });
    expect(events[1]!.data).toEqual({ message: "iteration 2", percentage: 20 });
  });

  it("drops callbacks fired outside a turn (no execution bound)", async () => {
    const { sender, events } = makeEventCapture();
    const reporter = makeReporter(sender, { level: "METADATA", maxBytes: null });

    await reporter.onToolEnd(toolRecord({ completedAt: 1_050 }));

    expect(events).toHaveLength(0);
  });

  it("passes sensitive content through only at FULL capture level", async () => {
    const { sender, events } = makeEventCapture();
    const reporter = makeReporter(sender, { level: "FULL", maxBytes: null });
    reporter.beginExecution(EXECUTION_ID);

    await reporter.onToolEnd(toolRecord({ result: "allowed-at-full", completedAt: 1_100 }));

    expect(events).toHaveLength(1);
    expect((events[0]!.data as Record<string, unknown>).result).toBe("allowed-at-full");
  });

  // ── golden shapes: frames parse against the zod schema ─────────

  it("emitted frames parse against executionEventPayloadSchema", async () => {
    const { sender, events } = makeEventCapture();
    const reporter = makeReporter(sender, { level: "METADATA", maxBytes: 262_144 });
    reporter.beginExecution(EXECUTION_ID);

    await reporter.onProgress(50, 5);
    await reporter.onToolStart(toolRecord({}));
    await reporter.onToolEnd(toolRecord({ completedAt: 1_042 }));

    for (const payload of events) {
      const parsed = executionEventPayloadSchema.safeParse(payload);
      expect(parsed.success).toBe(true);
    }
  });
});