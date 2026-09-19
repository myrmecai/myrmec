// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * §8.4/§15 rule 12 contract tests for the capture gate: the METADATA
 * allowlist, the FULL pass-through, fail-closed level handling, and the
 * maxBytes truncation ladder.
 */
import { describe, it, expect } from "vitest";
import { CaptureFilter } from "./CaptureFilter.js";
import type { CapturePolicy } from "../protocol/unifiedFrames.js";

const warnings: string[] = [];
const logger = {
  debug: () => {},
  info: () => {},
  warn: (msg: string) => warnings.push(msg),
  error: () => {},
};

describe("CaptureFilter (§8.4/§15 rule 12)", () => {
  // ── METADATA allowlist ─────────────────────────────────────────

  it("strips tool args and results from TOOL_COMPLETED at METADATA", () => {
    const filter = new CaptureFilter({ level: "METADATA", maxBytes: 262_144 }, logger);
    const out = filter.filterEvent("TOOL_COMPLETED", {
      toolName: "read_file",
      callId: "call-17",
      durationMs: 42,
      isError: false,
      // Sensitive content a naive caller might embed:
      args: { path: "/etc/passwd" },
      result: "root:x:0:0:...",
      prompt: "you are a helpful agent",
    });
    expect(out).toEqual({
      toolName: "read_file",
      callId: "call-17",
      durationMs: 42,
      isError: false,
    });
    expect(CaptureFilter.sensitiveKeysRemain(out)).toBe(false);
  });

  it("strips args from TOOL_STARTED at METADATA", () => {
    const filter = new CaptureFilter({ level: "METADATA", maxBytes: null }, logger);
    const out = filter.filterEvent("TOOL_STARTED", {
      toolName: "write_file",
      callId: "call-2",
      args: { path: "secrets.txt", content: "SECRET" },
    });
    expect(out).toEqual({ toolName: "write_file", callId: "call-2" });
  });

  it("keeps only the MODEL_USAGE token columns at METADATA", () => {
    const filter = new CaptureFilter({ level: "METADATA", maxBytes: null }, logger);
    const out = filter.filterEvent("MODEL_USAGE", {
      inputTokens: 1240,
      outputTokens: 310,
      providerPayload: { raw: "RESPONSE-BODY" },
    });
    expect(out).toEqual({ inputTokens: 1240, outputTokens: 310 });
  });

  it("bounds PROGRESS to message + percentage at METADATA", () => {
    const filter = new CaptureFilter({ level: "METADATA", maxBytes: null }, logger);
    const out = filter.filterEvent("PROGRESS", {
      message: "iteration 3",
      percentage: 30,
      transcript: "ENTIRE-MESSAGE-HISTORY",
    });
    expect(out).toEqual({ message: "iteration 3", percentage: 30 });
  });

  it("keeps the orchestration/verification/checkpoint metadata columns", () => {
    const filter = new CaptureFilter({ level: "METADATA", maxBytes: null }, logger);
    expect(
      filter.filterEvent("ORCHESTRATION_FUNCTION_STARTED", {
        callId: "c1",
        functionName: "invoke_worker",
        modelCode: "m",
        purpose: "IMPLEMENT",
        args: { instruction: "SECRET" },
      }),
    ).toEqual({ callId: "c1", functionName: "invoke_worker", modelCode: "m", purpose: "IMPLEMENT" });
    expect(
      filter.filterEvent("ORCHESTRATION_FUNCTION_COMPLETED", {
        callId: "c1",
        outcome: "COMPLETED",
        usage: { workerCalls: 1, rejectionCount: 0, totalTokens: 500 },
        workspaceRevision: 7,
        result: { summary: "SECRET" },
      }),
    ).toEqual({
      callId: "c1",
      outcome: "COMPLETED",
      usage: { workerCalls: 1, rejectionCount: 0, totalTokens: 500 },
      workspaceRevision: 7,
    });
    expect(
      filter.filterEvent("VERIFICATION_RECORDED", {
        verifierName: "tests",
        verdict: "PASS",
        candidateTreeHash: "abc",
        output: "SECRET",
      }),
    ).toEqual({ verifierName: "tests", verdict: "PASS", candidateTreeHash: "abc" });
    expect(
      filter.filterEvent("CHECKPOINT_CREATED", {
        commitHash: "def",
        treeHash: "abc",
        changedFileCount: 2,
        diff: "SECRET",
      }),
    ).toEqual({ commitHash: "def", treeHash: "abc", changedFileCount: 2 });
  });

  // ── unknown event types: conservative fallback ─────────────────

  it("keeps only toolName/callId-style identifiers for unknown event types", () => {
    const filter = new CaptureFilter({ level: "METADATA", maxBytes: null }, logger);
    const out = filter.filterEvent("SOMETHING_NEW", {
      toolName: "t",
      callId: "c1",
      args: { secret: true },
      providerPayload: "RAW",
    });
    expect(out).toEqual({ toolName: "t", callId: "c1" });
  });

  // ── FULL passes through (still truncated later) ────────────────

  it("passes everything through at FULL", () => {
    const filter = new CaptureFilter({ level: "FULL", maxBytes: null }, logger);
    const out = filter.filterEvent("TOOL_COMPLETED", {
      toolName: "read_file",
      callId: "call-17",
      args: { path: "x" },
      result: "content",
    });
    expect(out).toEqual({
      toolName: "read_file",
      callId: "call-17",
      args: { path: "x" },
      result: "content",
    });
    expect(CaptureFilter.sensitiveKeysRemain(out)).toBe(true);
  });

  // ── fail-closed handling ───────────────────────────────────────

  it("treats a null policy as METADATA (fail closed)", () => {
    const filter = new CaptureFilter(null, logger);
    expect(filter.level).toBe("METADATA");
    const out = filter.filterEvent("TOOL_COMPLETED", {
      toolName: "t",
      callId: "c1",
      args: { secret: "x" },
    });
    expect(out).toEqual({ toolName: "t", callId: "c1" });
  });

  it("fails closed to METADATA for an unknown level (logged once per value)", () => {
    warnings.length = 0;
    const filter = new CaptureFilter({ level: "EVERYTHING", maxBytes: null }, logger);
    expect(filter.level).toBe("METADATA");
    // Exactly one warning names the offending level value.
    const levelWarnings = warnings.filter((w) => w.includes("EVERYTHING"));
    expect(levelWarnings).toHaveLength(1);
    // The same level value is not warned about twice (one log per value).
    new CaptureFilter({ level: "EVERYTHING", maxBytes: null }, logger);
    expect(warnings.filter((w) => w.includes("EVERYTHING"))).toHaveLength(1);
  });

  // ── maxBytes ladder ────────────────────────────────────────────

  it("passes data under the budget unchanged", () => {
    const filter = new CaptureFilter({ level: "METADATA", maxBytes: 10_000 }, logger);
    const data = { toolName: "t", callId: "c1", durationMs: 5, isError: false };
    expect(filter.truncate(data, "TOOL_COMPLETED")).toEqual(data);
  });

  it("truncates long string values down to the budget while keeping required keys", () => {
    const filter = new CaptureFilter({ level: "FULL", maxBytes: 120 }, logger);
    const data = {
      toolName: "t",
      callId: "c1",
      result: "x".repeat(400),
      args: { blob: "y".repeat(400) },
    };
    const out = filter.truncate(data); // FULL: no type → no de-permitting
    const keys = Object.keys(out);
    expect(keys).toContain("toolName");
    expect(keys).toContain("callId");
    expect(CaptureFilter.encodedByteLength(out)).toBeLessThanOrEqual(120);
    // The bulk content shrank, but something honest survived.
    expect(JSON.stringify(out).length).toBeGreaterThan(2);
  });

  it("drops non-required keys first at METADATA when the budget cannot fit", () => {
    // A tiny budget: the required TOOL_COMPLETED metadata alone overflows it.
    const filter = new CaptureFilter({ level: "METADATA", maxBytes: 30 }, logger);
    const out = filter.truncate(
      { toolName: "very-long-tool-name", callId: "call-with-a-long-id", durationMs: 1, isError: false },
      "TOOL_COMPLETED",
    );
    // The ladder ends at {} when even the required columns don't fit.
    expect(out).toEqual({});
  });

  it("emits {} as the last resort when even the halving ladder cannot fit", () => {
    const filter = new CaptureFilter({ level: "FULL", maxBytes: 10 }, logger);
    const out = filter.truncate({ result: "z".repeat(1000) });
    expect(out).toEqual({});
  });
});