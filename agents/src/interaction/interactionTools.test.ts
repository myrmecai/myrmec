// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * interactionTools tests (design 14.2, protocol 22.6/22.7): the EXPLICIT
 * allowlist of interaction tools. Each tool's wire behavior is asserted:
 * names, argument schemas, the request_* tools' durable proposal +
 * engine-disposition return, and the no-mutation rule (the tools never
 * touch hold/cancel state themselves and never wait for human
 * confirmation inside the model turn).
 */
import { describe, expect, it } from "vitest";
import {
  INTERACTION_TOOL_NAMES,
  buildInteractionTools,
} from "./interactionTools.js";
import type { InteractionToolContext } from "./interactionTools.js";
import type {
  ExecutionControlRequestResolvedPayload,
} from "../protocol/unifiedFrames.js";
import type { ExecutionSnapshot } from "./ExecutionSnapshot.js";
import type { SafeExecutionEvent } from "./SafeExecutionEvent.js";
import type { Tool } from "../executor/types.js";

// ---- fixture helpers -------------------------------------------------

const EXECUTION_ID = "44444444-4444-4444-8444-444444444444";
const INTERACTION_ID = "66666666-6666-4666-8666-666666666666";

function snapshotFixture(
  overrides: Partial<ExecutionSnapshot> = {},
): Readonly<ExecutionSnapshot> {
  return Object.freeze({
    executionId: EXECUTION_ID,
    dispatchId: "55555555-5555-4555-8555-555555555555",
    workflowId: "11111111-1111-4111-8111-111111111111",
    runId: "22222222-2222-4222-8222-222222222222",
    stepId: "step-1",
    taskId: "33333333-3333-4333-8333-333333333333",
    attemptId: "55555555-5555-4555-8555-555555555555",
    attemptOrdinal: 1,
    holdState: "RUNNING",
    progressVersion: 3,
    progressCapturedAt: "2026-10-03T10:00:00.000Z",
    helperCallsCompleted: 2,
    verifierRejections: 1,
    budgetLimits: {
      maxWorkerCalls: 10,
      maxTokens: 100000,
      maxVerifierRejectionsPerAttempt: 3,
    },
    budgetTotal: { helperCalls: 2, totalTokens: 4200, rejectionCount: 1 },
    usageStatus: "KNOWN",
    ...overrides,
  });
}

function resolvedFixture(
  overrides: Partial<ExecutionControlRequestResolvedPayload> = {},
): ExecutionControlRequestResolvedPayload {
  return {
    executionId: EXECUTION_ID,
    dispatchId: "55555555-5555-4555-8555-555555555555",
    interactionId: INTERACTION_ID,
    controlRequestId: "77777777-7777-4777-8777-777777777777",
    resolutionRevision: 1,
    status: "ACCEPTED",
    expiresAt: null,
    commandMessageId: "cmd-1",
    controlRevision: 4,
    errorCode: null,
    ...overrides,
  };
}

/** Recording context stub: captures proposals, returns scripted
 * dispositions in order. */
function contextFixture(
  dispositions: ExecutionControlRequestResolvedPayload[] = [],
): InteractionToolContext & {
  proposals: Array<{ action: string; explanation?: string }>;
  snapshots: number;
} {
  const proposals: Array<{ action: string; explanation?: string }> = [];
  let snapshotReads = 0;
  const context = {
    getSnapshot: (): Readonly<ExecutionSnapshot> => {
      snapshotReads += 1;
      return snapshotFixture();
    },
    getRecentEvents: (limit: number): ReadonlyArray<SafeExecutionEvent> =>
      [
        { type: "CHECKPOINT_CREATED", at: "2026-10-03T10:00:01.000Z", data: { commitHash: "abc" } },
        { type: "VERIFICATION_RECORDED", at: "2026-10-03T10:00:02.000Z", data: { verdict: "APPROVED" } },
        { type: "PROGRESS", at: "2026-10-03T10:00:03.000Z", data: { helperCallsCompleted: 2 } },
      ].slice(-limit),
    requestControl: async (
      action: "HOLD" | "CONTINUE" | "CANCEL",
      explanation?: string,
    ): Promise<ExecutionControlRequestResolvedPayload> => {
      proposals.push({ action, ...(explanation !== undefined ? { explanation } : {}) });
      const next = dispositions.shift();
      if (!next) throw new Error("no scripted disposition left");
      return next;
    },
  };
  return Object.assign(context, { proposals, snapshots: snapshotReads });
}

function toolByName(tools: Tool[], name: string): Tool {
  const tool = tools.find((t) => t.name === name);
  expect(tool).toBeDefined();
  return tool as Tool;
}

// ---- allowlist shape -------------------------------------------------

describe("interactionTools allowlist (design 14.2 / 22.6)", () => {
  it("exposes EXACTLY the six allowlisted tools", () => {
    const tools = buildInteractionTools(contextFixture());
    expect(tools.map((t) => t.name).sort()).toEqual([
      "get_budget_usage",
      "get_execution_snapshot",
      "get_recent_events",
      "request_cancel",
      "request_continue",
      "request_hold",
    ]);
    expect([...INTERACTION_TOOL_NAMES].sort()).toEqual([
      "get_budget_usage",
      "get_execution_snapshot",
      "get_recent_events",
      "request_cancel",
      "request_continue",
      "request_hold",
    ]);
  });

  it("has NO workspace write / command / helper tools", () => {
    const tools = buildInteractionTools(contextFixture());
    const names = tools.map((t) => t.name);
    for (const forbidden of [
      "write_file",
      "read_file",
      "list_directory",
      "create_directory",
      "delete_file",
      "run_command",
      "invoke_helper",
      "report_verdict",
    ]) {
      expect(names).not.toContain(forbidden);
    }
  });
});

// ---- read-only tools -------------------------------------------------

describe("interactionTools read-only tools", () => {
  it("get_execution_snapshot returns the immutable snapshot", async () => {
    const context = contextFixture();
    const tool = toolByName(buildInteractionTools(context), "get_execution_snapshot");
    const result = (await tool.invoke({})) as Readonly<ExecutionSnapshot>;
    expect(result.executionId).toBe(EXECUTION_ID);
    expect(result.budgetTotal.totalTokens).toBe(4200);
    expect(result.usageStatus).toBe("KNOWN");
  });

  it("get_recent_events honors the limit parameter (newest last)", async () => {
    const context = contextFixture();
    const tool = toolByName(buildInteractionTools(context), "get_recent_events");
    const one = (await tool.invoke({ limit: 1 })) as SafeExecutionEvent[];
    expect(one).toHaveLength(1);
    expect(one[0]?.type).toBe("PROGRESS");
    const all = (await tool.invoke({ limit: 10 })) as SafeExecutionEvent[];
    expect(all).toHaveLength(3);
  });

  it("get_recent_events clamps an absurd limit to the bounded maximum", async () => {
    const context = contextFixture();
    const tool = toolByName(buildInteractionTools(context), "get_recent_events");
    const result = (await tool.invoke({ limit: 100000 })) as SafeExecutionEvent[];
    expect(result.length).toBeLessThanOrEqual(50);
  });

  it("get_budget_usage returns limits, totals and usage status only", async () => {
    const context = contextFixture();
    const tool = toolByName(buildInteractionTools(context), "get_budget_usage");
    const usage = (await tool.invoke({})) as {
      limits: { maxTokens: number };
      total: { helperCalls: number; totalTokens: number; rejectionCount: number };
      usageStatus: string;
      helperCallsCompleted: number;
    };
    expect(usage.limits.maxTokens).toBe(100000);
    expect(usage.total).toEqual({
      helperCalls: 2,
      totalTokens: 4200,
      rejectionCount: 1,
    });
    expect(usage.usageStatus).toBe("KNOWN");
    expect(usage.helperCallsCompleted).toBe(2);
  });
});

// ---- request_* tools: durable proposals, engine disposition-----------

describe("interactionTools request_* proposal tools (22.7)", () => {
  it("request_hold persists a durable proposal and returns the engine disposition", async () => {
    const context = contextFixture([
      resolvedFixture({ status: "ACCEPTED", controlRevision: 7 }),
    ]);
    const tool = toolByName(buildInteractionTools(context), "request_hold");
    const result = (await tool.invoke({
      explanation: "pause while the user reviews",
    })) as {
      status: string;
      controlRequestId: string;
      pending: boolean;
    };
    expect(result.status).toBe("ACCEPTED");
    expect(result.controlRequestId).toBeTruthy();
    expect(result.pending).toBe(false);
    expect(context.proposals).toEqual([
      { action: "HOLD", explanation: "pause while the user reviews" },
    ]);
  });

  it("request_continue routes its action through the proposal seam", async () => {
    const context = contextFixture([
      resolvedFixture({ status: "ACCEPTED" }),
    ]);
    const tool = toolByName(buildInteractionTools(context), "request_continue");
    const result = (await tool.invoke({})) as { status: string };
    expect(result.status).toBe("ACCEPTED");
    expect(context.proposals).toEqual([{ action: "CONTINUE" }]);
  });

  it("request_cancel returns CONFIRMATION_REQUIRED with a pending confirmation id, WITHOUT waiting for a user inside the turn", async () => {
    const expiresAt = "2026-10-03T10:05:00.000Z";
    const context = contextFixture([
      resolvedFixture({ status: "CONFIRMATION_REQUIRED", expiresAt }),
    ]);
    const tool = toolByName(buildInteractionTools(context), "request_cancel");
    const result = (await tool.invoke({
      explanation: "user asked to stop this attempt",
    })) as {
      status: string;
      controlRequestId: string;
      pending: boolean;
      expiresAt: string | null;
    };
    // The tool must NOT block on a human: the disposition returns
    // immediately and marks the confirmation as pending in the answer.
    expect(result.status).toBe("CONFIRMATION_REQUIRED");
    expect(result.controlRequestId).toBeTruthy();
    expect(result.pending).toBe(true);
    expect(result.expiresAt).toBe(expiresAt);
    expect(context.proposals).toEqual([
      { action: "CANCEL", explanation: "user asked to stop this attempt" },
    ]);
  });

  it("a REFUSED proposal returns the engine's refusal honestly (no fabricated success)", async () => {
    const context = contextFixture([
      resolvedFixture({ status: "REJECTED", errorCode: "FORBIDDEN" }),
    ]);
    const tool = toolByName(buildInteractionTools(context), "request_hold");
    const result = (await tool.invoke({})) as { status: string; pending: boolean };
    expect(result.status).toBe("REJECTED");
    expect(result.pending).toBe(false);
  });

  it("request tools ignore unknown extra arguments (bounded schemas)", async () => {
    const context = contextFixture([resolvedFixture()]);
    const tool = toolByName(buildInteractionTools(context), "request_cancel");
    const result = (await tool.invoke({
      explanation: "x",
      rogueArgument: "ignored",
    })) as { status: string };
    expect(result.status).toBe("ACCEPTED");
    expect(context.proposals).toEqual([
      { action: "CANCEL", explanation: "x" },
    ]);
  });
});