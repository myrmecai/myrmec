// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * PolicyEnforcer (§8.7, A4): per-execution monotonic local accounting +
 * tighten-only allowance enforcement.
 *
 * <p>Every execution (a conversation turn or an orchestration attempt) keeps
 * LOCAL accounting of the two §8.7 axes — orchestration function calls and
 * total tokens. The engine's durably accounted values ride
 * {@code execution.policy.update}; the enforcer compares them against the
 * local counters and:</p>
 *
 * <ul>
 *   <li>REJECTS an update whose usage would roll the local accounting
 *       backward (the engine accounts accepted usage events — a lower value
 *       is a protocol violation). §8.7 defines no dedicated rejection frame,
 *       so the host answers {@code protocol.error INVALID_MESSAGE} with the
 *       offending messageId and a reason (recorded deviation).</li>
 *   <li>Applies {@code allowance.maxTokens} tighten-only: an allowance that
 *       loosens the current limit is rejected the same way.</li>
 *   <li>Reports the CEILING when local accounting reaches an enforced limit
 *       ({@code allowance.maxTokens} or the session's
 *       {@code policy.maxIterations}): the executor pauses work — per §8.7
 *       pause is the terminal answer, and NO further model calls pass the
 *       ceiling until the execution is resumed.</li>
 * </ul>
 */
import type { Logger } from "../models/index.js";

/** §8.7 frame usage block (the engine's durably accounted value). */
export interface EngineUsage {
  orchestrationFunctionCalls: number;
  totalTokens: number;
}

/** §8.7 frame allowance block. */
export interface EngineAllowance {
  maxTokens?: number | null;
}

/** §7.3 session.open policy block. */
export interface SessionPolicyLimits {
  maxIterations?: number | null;
  executionTimeoutSeconds?: number | null;
}

/** A decision the executor acts on at its next loop boundary. */
export type EnforcementDecision =
  | { kind: "ok" }
  | {
      kind: "paused";
      reason: "TOKEN_ALLOWANCE_EXCEEDED" | "ITERATION_LIMIT";
      message: string;
    };

/** Validation of one inbound execution.policy.update frame. */
export type PolicyUpdateVerdict =
  | { kind: "accepted"; allowanceMaxTokens: number | null }
  | { kind: "rejected"; reason: string };

export class PolicyEnforcer {
  /** §8.7 local monotonic accounting (host-owned). */
  private functionCalls = 0;
  private tokens = 0;
  /** The active tighten-only token allowance (null = none enforced). */
  private allowanceMaxTokens: number | null;
  /** The session's iteration ceiling (from session.open policy). */
  private readonly maxIterations: number | null;
  private readonly log: Logger;

  constructor(options: {
    /** The session.open policy — maxIterations is enforced from open. */
    policy?: SessionPolicyLimits | null;
    /** Initial allowance (a session may open already-constrained). */
    initialAllowanceMaxTokens?: number | null;
    logger?: Logger;
  }) {
    this.maxIterations = options.policy?.maxIterations ?? null;
    this.allowanceMaxTokens = options.initialAllowanceMaxTokens ?? null;
    this.log = options.logger ?? console;
  }

  /** The current token allowance (null = no token ceiling). */
  get maxTokens(): number | null {
    return this.allowanceMaxTokens;
  }

  /** The current iteration ceiling (null = none). */
  get iterationLimit(): number | null {
    return this.maxIterations;
  }

  /** The local monotonic accounting snapshot (§8.7 host-side usage). */
  accounting(): EngineUsage {
    return {
      orchestrationFunctionCalls: this.functionCalls,
      totalTokens: this.tokens,
    };
  }

  /** Record one orchestration function call (the runner counts each). */
  recordFunctionCall(): void {
    this.functionCalls += 1;
  }

  /** Record consumed tokens (provider-reported response deltas). */
  recordTokens(delta: number): void {
    if (Number.isInteger(delta) && delta >= 0) {
      this.tokens += delta;
    }
  }

  /**
   * Validate one inbound execution.policy.update payload (§8.7):
   * usage must not roll the local accounting backward, and the allowance
   * may only preserve or tighten the current limit. An accepted frame
   * returns the (possibly null) enforced ceiling.
   */
  applyPolicyUpdate(usage: EngineUsage, allowance?: EngineAllowance | null): PolicyUpdateVerdict {
    if (usage.orchestrationFunctionCalls < this.functionCalls) {
      return {
        kind: "rejected",
        reason: `execution.policy.update rolls orchestrationFunctionCalls backward: engine reports ${usage.orchestrationFunctionCalls}, local accounting is ${this.functionCalls}`,
      };
    }
    if (usage.totalTokens < this.tokens) {
      return {
        kind: "rejected",
        reason: `execution.policy.update rolls totalTokens backward: engine reports ${usage.totalTokens}, local accounting is ${this.tokens}`,
      };
    }
    const newLimit = allowance?.maxTokens ?? null;
    if (newLimit !== null && this.allowanceMaxTokens !== null
        && newLimit > this.allowanceMaxTokens) {
      return {
        kind: "rejected",
        reason: `execution.policy.update loosens the allowance: ${newLimit} > current ${this.allowanceMaxTokens} (tighten-only, §8.7)`,
      };
    }
    // §13 alignment: the engine's durable accounting is authoritative —
    // reconcile the local counters upward (never downward) so the ceiling
    // math reflects accepted usage even when local deltas lag the engine.
    this.functionCalls = Math.max(this.functionCalls, usage.orchestrationFunctionCalls);
    this.tokens = Math.max(this.tokens, usage.totalTokens);
    this.allowanceMaxTokens = newLimit;
    return { kind: "accepted", allowanceMaxTokens: newLimit };
  }

  /**
   * The next-boundary decision: is any enforced ceiling reached? Callers
   * (InferenceExecutor / OrchestrationRunner) check BEFORE each model call
   * and pause the execution when a ceiling reports.
   */
  check(): EnforcementDecision {
    if (this.allowanceMaxTokens !== null && this.tokens >= this.allowanceMaxTokens) {
      return {
        kind: "paused",
        reason: "TOKEN_ALLOWANCE_EXCEEDED",
        message: `execution paused: accounted usage ${this.tokens} reached the allowance ceiling ${this.allowanceMaxTokens} (§8.7 — pause until resumed)`,
      };
    }
    if (this.maxIterations !== null && this.functionCalls >= this.maxIterations) {
      return {
        kind: "paused",
        reason: "ITERATION_LIMIT",
        message: `execution paused: ${this.functionCalls} function calls reached the policy ceiling ${this.maxIterations}`,
      };
    }
    return { kind: "ok" };
  }

  /** §13-style breach log; call after a pause decision is taken. */
  notePause(decision: Extract<EnforcementDecision, { kind: "paused" }>): void {
    this.log.warn(`PolicyEnforcer ceiling: ${decision.message}`);
  }
}