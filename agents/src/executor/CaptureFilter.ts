// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * CaptureFilter (§8.4 + §15 rule 12): gates what the host may emit on the
 * `execution.event` stream.
 *
 * <p>The doc contract: "Tool arguments, tool results, prompts, and provider
 * payloads are included only when capture policy permits. The normal event
 * stream stores metadata." and "Capture policy limits what sensitive data
 * the host may emit; engine sampling independently limits what is
 * persisted."</p>
 *
 * <p>The engine always populates `session.open`'s `capture` block (default
 * `{ level: "METADATA", maxBytes: 262144 }`, config
 * `myrmec.session.capture.level/max-bytes`). A null/absent policy is treated
 * as METADATA (fail closed) rather than permissive: the SDK must never leak
 * sensitive content merely because a peer omitted the block.</p>
 */
import type { CapturePolicy } from "../protocol/unifiedFrames.js";
import type { Logger } from "../models/index.js";

/**
 * §8.4 event types → the REQUIRED metadata columns that may pass at
 * METADATA. Everything else — tool arguments, tool results, prompts,
 * provider payloads — is stripped. Unknown event types take the
 * conservative {@link FALLBACK_KEYS}.
 */
const METADATA_ALLOWLIST: Readonly<Record<string, readonly string[]>> = {
  TOOL_STARTED: ["toolName", "callId"],
  TOOL_COMPLETED: ["toolName", "callId", "durationMs", "isError"],
  MODEL_STARTED: ["modelId"],
  MODEL_USAGE: ["inputTokens", "outputTokens"],
  PROGRESS: ["message", "percentage"],
  ORCHESTRATION_FUNCTION_STARTED: ["callId", "functionName", "modelCode", "purpose"],
  ORCHESTRATION_FUNCTION_COMPLETED: [
    "callId",
    "outcome",
    "usage",
    "workspaceRevision",
  ],
  VERIFICATION_RECORDED: ["verifierName", "verdict", "candidateTreeHash"],
  CHECKPOINT_CREATED: ["commitHash", "treeHash", "changedFileCount"],
  CAPTURE_DETAIL: ["captureLevel", "detail"],
};

/**
 * Conservative fallback for event types outside the §8.4 table: only
 * identifier-style keys pass (toolName/callId). An unknown type may never
 * widen what the host emits.
 */
const FALLBACK_KEYS: readonly string[] = ["toolName", "callId"];

/**
 * Keys that carry sensitive content under §15 rule 12. Used by
 * {@link CaptureFilter.sensitiveKeysRemain} so tests can assert tool
 * arguments/results were stripped.
 */
const SENSITIVE_KEYS: readonly string[] = [
  "args",
  "result",
  "error",
  "prompt",
  "prompts",
  "messages",
  "providerPayload",
];

/** The effective capture level after fail-closed normalization. */
export type CaptureLevel = "METADATA" | "FULL";

/** Levels already warned about (one log per unknown level value, process-wide). */
const warnedLevels = new Set<string>();

export class CaptureFilter {
  /** The effective level after fail-closed normalization (see ctor). */
  readonly level: CaptureLevel;
  /** The emit budget in bytes; null = unbounded (still allowlist-filtered). */
  readonly maxBytes: number | null;
  private readonly log: Logger;

  /**
   * Build a filter from the session's capture policy.
   *
   * @param policy the session.open `capture` block; null/absent ⇒ METADATA
   *   (fail closed — the engine always populates the block, so a null here
   *   means "unknown policy", never "permitted").
   * @param logger warned once per unknown level value; defaults to console.
   */
  constructor(policy: CapturePolicy | null, logger?: Logger) {
    this.log = logger ?? console;
    this.maxBytes = policy?.maxBytes ?? null;
    const raw = policy?.level;
    if (raw === "FULL") {
      this.level = "FULL";
      return;
    }
    // Unknown level → fail closed to METADATA; log once per level value.
    if (raw !== undefined && raw !== null && raw !== "" && raw !== "METADATA") {
      if (!warnedLevels.has(raw)) {
        warnedLevels.add(raw);
        this.log.warn(
          `CaptureFilter: unknown capture level '${raw}' — failing closed to METADATA`,
        );
      }
    }
    this.level = "METADATA";
  }

  /**
   * §8.4 metadata gate: keep only the per-type REQUIRED metadata columns at
   * METADATA (or the conservative fallback identifiers for unknown types);
   * pass everything through at FULL. Stripped keys never reach any sender.
   */
  filterEvent(type: string, data: Record<string, unknown>): Record<string, unknown> {
    if (this.level === "FULL") {
      return { ...data };
    }
    const allowed = METADATA_ALLOWLIST[type] ?? FALLBACK_KEYS;
    const out: Record<string, unknown> = {};
    for (const key of allowed) {
      if (key in data) {
        out[key] = data[key];
      }
    }
    return out;
  }

  /**
   * maxBytes ladder (applied to ALREADY-FILTERED data):
   *
   *   1. Drop non-required keys — only when the caller passes the event
   *      `type` (belt-and-braces: at METADATA the allowlist already ran; at
   *      FULL the data is permitted content and is shrunk, never silently
   *      de-permitted, when no type is supplied).
   *   2. Truncate string values: each pass halves every string value
   *      (bottoming out at 1 char) until the serialization fits the budget.
   *   3. Last resort: emit `{}` — the frame stays schema-valid with empty
   *      data; the required columns are the honest casualty of an
   *      unmeetable budget (logged).
   *
   * No maxBytes ⇒ data passes unchanged.
   */
  truncate(
    data: Record<string, unknown>,
    type?: string,
    budget?: number,
  ): Record<string, unknown> {
    // An explicit budget overrides maxBytes: callers that embed the data map
    // inside a larger envelope pass the envelope-remaining budget here (the
    // sink subtracts the fixed-key overhead from maxBytes first).
    const effectiveBudget = budget ?? this.maxBytes;
    if (effectiveBudget === null) {
      return type !== undefined ? this.filterEvent(type, data) : data;
    }
    if (effectiveBudget <= 0) {
      this.log.warn(
        `CaptureFilter: no data budget left (${effectiveBudget}B) — emitting empty data`,
      );
      return {};
    }
    let current = type !== undefined ? this.filterEvent(type, data) : data;
    if (CaptureFilter.encodedByteLength(current) <= effectiveBudget) {
      return current;
    }
    // Rung 2: progressively halve the string values until it fits.
    for (let pass = 0; pass < 24; pass++) {
      current = halveStringValues(current) as Record<string, unknown>;
      if (CaptureFilter.encodedByteLength(current) <= effectiveBudget) {
        return current;
      }
      if (!hasMulticharString(current)) {
        break;
      }
    }
    // Rung 3: the budget cannot be met — emit the schema-valid empty map.
    this.log.warn(
      `CaptureFilter: event data exceeded budget=${effectiveBudget}B — emitting empty data`,
    );
    return {};
  }

  /**
   * True when any sensitive key (tool args/results, prompts, provider
   * payloads) is still present at the top level or one level nested — the
   * assertion helper for tests that the metadata stream stayed metadata.
   */
  static sensitiveKeysRemain(data: Record<string, unknown>): boolean {
    for (const key of Object.keys(data)) {
      if (SENSITIVE_KEYS.includes(key)) {
        return true;
      }
      const value = data[key];
      if (value !== null && typeof value === "object" && !Array.isArray(value)) {
        if (Object.keys(value).some((k) => SENSITIVE_KEYS.includes(k))) {
          return true;
        }
      }
    }
    return false;
  }

  /** JSON byte length of a value; non-serializable values count as unbounded. */
  static encodedByteLength(data: unknown): number {
    try {
      return Buffer.byteLength(JSON.stringify(data));
    } catch {
      return Number.POSITIVE_INFINITY;
    }
  }

  /**
   * The minimal data value a payload carries (an empty object) — callers
   * measuring their envelope's fixed-key overhead serialize
   * `{...fixedKeys, ...EMPTY_PLACEHOLDER}` and subtract from maxBytes.
   */
  static readonly EMPTY_PLACEHOLDER: Record<string, never> = {};
}

/** Return a copy of the value with every string halved in length. */
function halveStringValues(value: unknown): unknown {
  if (typeof value === "string") {
    return value.slice(0, Math.max(1, Math.floor(value.length / 2)));
  }
  if (Array.isArray(value)) {
    return value.map(halveStringValues);
  }
  if (value !== null && typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const [key, inner] of Object.entries(value as Record<string, unknown>)) {
      out[key] = halveStringValues(inner);
    }
    return out;
  }
  return value;
}

/** True when any nested string still has more than one character. */
function hasMulticharString(value: unknown): boolean {
  if (typeof value === "string") {
    return value.length > 1;
  }
  if (Array.isArray(value)) {
    return value.some(hasMulticharString);
  }
  if (value !== null && typeof value === "object") {
    return Object.values(value).some(hasMulticharString);
  }
  return false;
}