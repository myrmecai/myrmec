// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * §8.7 (A4) enforcement contract tests: monotonic accounting,
 * tighten-only allowance, ceiling pause, and the §7.3 policy seeding.
 */
import { describe, it, expect, vi } from "vitest";
import { PolicyEnforcer } from "./PolicyEnforcer.js";
import type { EnforcementDecision } from "./PolicyEnforcer.js";

const silentLogger = {
  debug: () => {},
  info: () => {},
  warn: () => {},
  error: () => {},
};

describe("PolicyEnforcer (§8.7)", () => {
  it("accepts an advance and records it as the enforced allowance", () => {
    const enforcer = new PolicyEnforcer({ logger: silentLogger });

    const verdict = enforcer.applyPolicyUpdate(
      { orchestrationFunctionCalls: 4, totalTokens: 8200 },
      { maxTokens: 150_000 },
    );

    expect(verdict).toEqual({ kind: "accepted", allowanceMaxTokens: 150_000 });
    expect(enforcer.maxTokens).toBe(150_000);
    // §13 alignment: local accounting reconciles upward to the engine's view.
    expect(enforcer.accounting()).toEqual({
      orchestrationFunctionCalls: 4,
      totalTokens: 8200,
    });
  });

  it("rejects a backward usage roll (§8.7: the host rejects a backward roll)", () => {
    const enforcer = new PolicyEnforcer({ logger: silentLogger });
    enforcer.recordTokens(5_000);

    const verdict = enforcer.applyPolicyUpdate(
      { orchestrationFunctionCalls: 0, totalTokens: 100 },
      { maxTokens: 10_000 },
    );

    expect(verdict.kind).toBe("rejected");
    if (verdict.kind === "rejected") {
      expect(verdict.reason).toContain("totalTokens");
    }
    // The rejected update changed nothing.
    expect(enforcer.maxTokens).toBeNull();
    expect(enforcer.accounting().totalTokens).toBe(5_000);
  });

  it("rejects a backward function-call roll", () => {
    const enforcer = new PolicyEnforcer({ logger: silentLogger });
    enforcer.recordFunctionCall();
    enforcer.recordFunctionCall();
    enforcer.recordFunctionCall();

    const verdict = enforcer.applyPolicyUpdate(
      { orchestrationFunctionCalls: 1, totalTokens: 999_999 },
    );

    expect(verdict.kind).toBe("rejected");
    if (verdict.kind === "rejected") {
      expect(verdict.reason).toContain("orchestrationFunctionCalls");
    }
  });

  it("rejects an allowance that loosens the current limit (tighten-only)", () => {
    const enforcer = new PolicyEnforcer({
      logger: silentLogger,
      initialAllowanceMaxTokens: 50_000,
    });

    const verdict = enforcer.applyPolicyUpdate(
      { orchestrationFunctionCalls: 1, totalTokens: 1_000 },
      { maxTokens: 100_000 },
    );

    expect(verdict.kind).toBe("rejected");
    if (verdict.kind === "rejected") {
      expect(verdict.reason).toContain("tighten-only");
    }
    expect(enforcer.maxTokens).toBe(50_000);
  });

  it("accepts an equal allowance (preserve) and a tighter one", () => {
    const enforcer = new PolicyEnforcer({
      logger: silentLogger,
      initialAllowanceMaxTokens: 50_000,
    });

    expect(
      enforcer.applyPolicyUpdate(
        { orchestrationFunctionCalls: 1, totalTokens: 10 },
        { maxTokens: 50_000 },
      ).kind,
    ).toBe("accepted");
    expect(
      enforcer.applyPolicyUpdate(
        { orchestrationFunctionCalls: 2, totalTokens: 20 },
        { maxTokens: 1_000 },
      ).kind,
    ).toBe("accepted");
    expect(enforcer.maxTokens).toBe(1_000);
  });

  it("pauses at the allowance ceiling and reports no further model work", () => {
    const enforcer = new PolicyEnforcer({
      logger: silentLogger,
      initialAllowanceMaxTokens: 1_000,
    });

    expect(enforcer.check().kind).toBe("ok");
    enforcer.recordTokens(1_000);
    const decision: EnforcementDecision = enforcer.check();
    expect(decision).toMatchObject({
      kind: "paused",
      reason: "TOKEN_ALLOWANCE_EXCEEDED",
    });
  });

  it("honors a tighten to below the accounted usage with an immediate pause", () => {
    const enforcer = new PolicyEnforcer({ logger: silentLogger });
    enforcer.recordTokens(5_000);

    const verdict = enforcer.applyPolicyUpdate(
      { orchestrationFunctionCalls: 0, totalTokens: 5_000 },
      { maxTokens: 2_000 },
    );
    expect(verdict.kind).toBe("accepted");

    expect(enforcer.check()).toMatchObject({
      kind: "paused",
      reason: "TOKEN_ALLOWANCE_EXCEEDED",
    });
  });

  it("pauses at the session's maxIterations policy ceiling", () => {
    const enforcer = new PolicyEnforcer({
      logger: silentLogger,
      policy: { maxIterations: 3 },
    });

    enforcer.recordFunctionCall();
    enforcer.recordFunctionCall();
    expect(enforcer.check().kind).toBe("ok");
    enforcer.recordFunctionCall();
    expect(enforcer.check()).toMatchObject({
      kind: "paused",
      reason: "ITERATION_LIMIT",
    });
  });

  it("records token deltas only for finite non-negative values", () => {
    const enforcer = new PolicyEnforcer({ logger: silentLogger });
    enforcer.recordTokens(Number.NaN);
    enforcer.recordTokens(-5);
    enforcer.recordTokens(10.5);
    expect(enforcer.accounting().totalTokens).toBe(0);
    enforcer.recordTokens(42);
    expect(enforcer.accounting().totalTokens).toBe(42);
  });
});