// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * ApprovalPolicyEvaluator (design §7.2/§17.4, HITL slice A): combines the
 * pinned Profile's `approvalPolicy`, command-template `riskClass` overrides,
 * and the project `autoHitlOnDestructive` setting into one decision for a
 * governed action.
 *
 * <p>Precedence (§17.4): Profile {@code DENY} → terminal
 * {@code APPROVAL_POLICY_DENIED} (no request created);
 * {@code REQUIRE_APPROVAL} → suspend regardless of the project setting;
 * otherwise the risk matrix applies — a SAFE action executes, a
 * DESTRUCTIVE/IRREVERSIBLE action suspends only when the project HITL
 * setting requires approval. Policy can only tighten the matrix floor,
 * never weaken it.</p>
 *
 * <p>Effective risk derivation (§17.4): the five orchestration tools are
 * SAFE by default; a Profile command template may declare a higher
 * {@code riskClass} override. Evaluation is per worker invocation — the
 * effective risk of one {@code invoke_worker} is the highest risk across
 * the intersection of that worker's declared tools and command templates.
 * Runner-owned checkpoint is DESTRUCTIVE (fixed); remote push is
 * IRREVERSIBLE (fixed).</p>
 */
import { createHash } from "node:crypto";
import type {
  OrchestrationAssignment,
  WorkerAuthoring,
} from "./types.js";
import type { GovernedAction } from "./GovernedAction.js";

export type ApprovalPolicyDecision =
  | { outcome: "ALLOW" }
  | { outcome: "DENY"; reasonCode: "APPROVAL_POLICY_DENIED"; reason: string }
  | { outcome: "REQUIRE_APPROVAL"; expiresAt: string };

/** The project HITL matrix input (§17.4): autoHitlOnDestructive. */
export interface ApprovalPolicyEvaluatorOptions {
  /** The project's autoHitlOnDestructive setting — matrix input for
   * DESTRUCTIVE/IRREVERSIBLE actions without an explicit profile rule. */
  autoHitlOnDestructive: boolean;
  /** Default approval-request TTL seconds (§17.4 expiry). */
  approvalTtlSeconds?: number;
}

/** Policy keys use the prefixed identifier forms (§17.4). */
const TOOL_KEY = (tool: string): string => `tool:${tool}`;
const TEMPLATE_KEY = (template: string): string => `template:${template}`;
const ACTION_KEY = (action: string): string => `action:${action}`;

export class ApprovalPolicyEvaluator {
  private readonly options: ApprovalPolicyEvaluatorOptions;

  constructor(options: ApprovalPolicyEvaluatorOptions) {
    this.options = options;
  }

  /**
   * Evaluate one governed action against the assignment's pinned policy.
   * Deterministic and synchronous — no external state (the digests bind
   * the exact action; the sink owns durability).
   */
  evaluate(assignment: OrchestrationAssignment, action: GovernedAction): ApprovalPolicyDecision {
    const policy = assignment.policy.approvalPolicy ?? {};

    // §17.4 precedence (1): an explicit profile DENY always denies —
    // approval can never override a denied action; no request is created.
    const denyKey = this.policyKeyFor(assignment, action);
    if (denyKey && policy[denyKey] === "DENY") {
      return {
        outcome: "DENY",
        reasonCode: "APPROVAL_POLICY_DENIED",
        reason: `profile approvalPolicy denies ${denyKey}`,
      };
    }

    // §17.4 precedence (2): an explicit REQUIRE_APPROVAL suspends
    // regardless of the project setting.
    if (denyKey && policy[denyKey] === "REQUIRE_APPROVAL") {
      return {
        outcome: "REQUIRE_APPROVAL",
        expiresAt: this.expiry(),
      };
    }

    // §17.4 precedence (3): the risk matrix. SAFE executes; the fixed
    // DESTRUCTIVE checkpoint and IRREVERSIBLE push suspend only when the
    // project HITL setting requires approval; a worker invocation's
    // effective risk was already folded into the action's riskClass.
    if (action.riskClass === "SAFE") {
      return { outcome: "ALLOW" };
    }
    if (this.options.autoHitlOnDestructive) {
      return {
        outcome: "REQUIRE_APPROVAL",
        expiresAt: this.expiry(),
      };
    }
    return { outcome: "ALLOW" };
  }

  /**
   * The effective risk of one worker invocation (§17.4): the highest risk
   * across the intersection of the worker's declared tools and command
   * templates. The five tools are SAFE by default; a command template may
   * override to DESTRUCTIVE/IRREVERSIBLE.
   */
  effectiveWorkerRisk(
    assignment: OrchestrationAssignment,
    worker: WorkerAuthoring,
  ): "SAFE" | "DESTRUCTIVE" | "IRREVERSIBLE" {
    let risk: "SAFE" | "DESTRUCTIVE" | "IRREVERSIBLE" = "SAFE";
    for (const command of worker.allowedCommands) {
      const template = assignment.policy.commandTemplates[command];
      const templateRisk = template?.riskClass ?? "SAFE";
      if (templateRisk === "IRREVERSIBLE") return "IRREVERSIBLE";
      if (templateRisk === "DESTRUCTIVE") risk = "DESTRUCTIVE";
    }
    return risk;
  }

  /**
   * The policy key for an action (§17.4 identifier forms): the worker-
   * invocation action resolves to the HIGHEST-RISK referenced key so a
   * REQUIRE_APPROVAL on any participating template/tool suspends.
   */
  private policyKeyFor(
    assignment: OrchestrationAssignment,
    action: GovernedAction,
  ): string | null {
    const policy = assignment.policy.approvalPolicy ?? {};
    if (action.type === "CHECKPOINT") return ACTION_KEY("CHECKPOINT");
    if (action.type === "PUSH") return ACTION_KEY("PUSH");
    if (action.type === "CONTINUE_BUDGET") return null;
    if (action.type !== "WORKER_TOOL") return null;

    // WORKER_TOOL: check every referenced tool and template; the first
    // explicit DENY/REQUIRE_APPROVAL entry wins (tightest rule).
    const workerName = action.summary.match(/^worker:([^:]+)/)?.[1];
    const worker = assignment.step.orchestration.workers.find(
      (w) => w.name === workerName,
    );
    if (!worker) return null;
    const keys = [
      ...worker.allowedTools.map(TOOL_KEY),
      ...worker.allowedCommands.map(TEMPLATE_KEY),
    ];
    for (const key of keys) {
      if (policy[key] === "DENY" || policy[key] === "REQUIRE_APPROVAL") {
        return key;
      }
    }
    return null;
  }

  private expiry(): string {
    const ttlMs = (this.options.approvalTtlSeconds ?? 3600) * 1000;
    return new Date(Date.now() + ttlMs).toISOString();
  }
}

/**
 * The §7.2 governed-action digest: SHA-256 over the runner-owned identity
 * fields (actionId, type, riskClass, summary) — the model never provides
 * or influences it.
 */
export function governedActionDigest(action: {
  actionId: string;
  type: string;
  riskClass: string;
  summary: string;
}): string {
  return createHash("sha256")
    .update(`${action.actionId}:${action.type}:${action.riskClass}:${action.summary}`)
    .digest("hex");
}