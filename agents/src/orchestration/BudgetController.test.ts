// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * BudgetController contract tests (design §13): counter increments,
 * enforcement points, one-response overshoot, sticky breach flagging, and
 * counter restoration.
 */
import { describe, it, expect } from "vitest";
import { InMemoryBudgetController } from "./BudgetController.js";

function controller(over: { calls?: number; tokens?: number; rejections?: number } = {}) {
  return new InMemoryBudgetController({
    maxWorkerCalls: over.calls ?? 2,
    maxTokens: over.tokens ?? 100,
    maxVerifierRejectionsPerAttempt: over.rejections ?? 1,
  });
}

describe("InMemoryBudgetController", () => {
  it("accepts worker calls while under maxWorkerCalls and rejects the one over without counting it", () => {
    const c = controller({ calls: 2 });
    expect(c.tryReserveWorkerCall()).toBeNull();
    expect(c.tryReserveWorkerCall()).toBeNull();
    // The third delegation is rejected — and never counted as an
    // accepted execution (§13: the counter counts accepted executions).
    const breach = c.tryReserveWorkerCall();
    expect(breach).not.toBeNull();
    expect(breach!.errorCode).toBe("WORKER_BUDGET_EXCEEDED");
    expect(c.counters().workerCalls).toBe(2);
  });

  it("records tokens after each response and breaches before the next model call", () => {
    const c = controller({ tokens: 100 });
    c.recordTokens(60);
    expect(c.check("before-model-call")).toBeNull();
    c.recordTokens(60); // overshoot: known only after the response
    const breach = c.check("before-model-call");
    expect(breach).not.toBeNull();
    expect(breach!.errorCode).toBe("TOKEN_BUDGET_EXCEEDED");
  });

  it("flags the rejection breach and reports it at every later boundary", () => {
    const c = controller({ rejections: 1 });
    expect(c.recordRejection()).toBeNull();
    const breach = c.recordRejection();
    expect(breach).not.toBeNull();
    expect(breach!.errorCode).toBe("REJECTION_BUDGET_EXCEEDED");
    // Sticky: the same breach is reported at any later checkpoint, so
    // nested model loops stop at their next boundary (§13).
    expect(c.check("before-model-call")).toBe(breach);
    expect(c.check("before-tool-execution")).toBe(breach);
  });

  it("a flagged worker-call breach sticks across all later checks", () => {
    const c = controller({ calls: 1 });
    expect(c.tryReserveWorkerCall()).toBeNull();
    const breach = c.tryReserveWorkerCall();
    expect(breach!.errorCode).toBe("WORKER_BUDGET_EXCEEDED");
    expect(c.check("before-model-call")).toBe(breach);
  });

  it("ignores non-integer and negative token recordings (never estimated)", () => {
    const c = controller({ tokens: 10 });
    c.recordTokens(-5);
    c.recordTokens(1.5);
    expect(c.counters().totalTokens).toBe(0);
    c.recordTokens(7);
    expect(c.counters().totalTokens).toBe(7);
  });

  it("restores counters for a same-dispatch restart so a crash cannot reset a limit", () => {
    const restored = new InMemoryBudgetController(
      { maxWorkerCalls: 2, maxTokens: 100, maxVerifierRejectionsPerAttempt: 1 },
      { workerCalls: 2, totalTokens: 80, rejectionCount: 1 },
    );
    // The token budget is already exceeded by restored counters.
    restored.recordTokens(30);
    expect(restored.check("before-model-call")!.errorCode).toBe("TOKEN_BUDGET_EXCEEDED");
    // The next delegation breaches without counting.
    expect(restored.tryReserveWorkerCall()!.errorCode).toBe("WORKER_BUDGET_EXCEEDED");
    expect(restored.recordRejection()!.errorCode).toBe("REJECTION_BUDGET_EXCEEDED");
    expect(restored.counters()).toEqual({
      workerCalls: 2,
      totalTokens: 110,
      rejectionCount: 2,
    });
    // Sticky from here: every later check reports the first flagged
    // breach (the worker-call one).
    expect(restored.check("before-model-call")!.errorCode).toBe("WORKER_BUDGET_EXCEEDED");
  });

  it("exposes the effective limits", () => {
    const c = controller({ calls: 5, tokens: 500, rejections: 3 });
    expect(c.limits()).toEqual({
      maxWorkerCalls: 5,
      maxTokens: 500,
      maxVerifierRejectionsPerAttempt: 3,
    });
  });
});