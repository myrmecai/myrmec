// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { describe, it, expect, vi } from "vitest";
import {
  ApprovalCoordinator,
  ApprovalTimeoutError,
} from "./ApprovalCoordinator.js";
import type { ExecutionApprovalRequestedPayload } from "../protocol/unifiedFrames.js";

/** Records the unified sender's frames. */
function recorder() {
  const payloads: ExecutionApprovalRequestedPayload[] = [];
  const sender = {
    sendExecutionApprovalRequested: (p: ExecutionApprovalRequestedPayload) => {
      payloads.push(p);
      return Promise.resolve();
    },
  };
  return { payloads, sender };
}

/** A deterministic id generator so tests can correlate request/decision. */
function fixedIds(...ids: string[]) {
  let i = 0;
  return () => ids[i++] ?? `extra_${i}`;
}

describe("ApprovalCoordinator (unified wire, P6-T6)", () => {
  it("emits execution.approval.requested stamping the client request id", async () => {
    const { payloads, sender } = recorder();
    const c = new ApprovalCoordinator({ sender, generateId: fixedIds("r1") });

    const pending = c.requestApproval({
      executionId: "e1",
      content: "Delete file?",
      payload: { path: "/tmp/x" },
    });

    await vi.waitFor(() => expect(payloads.length).toBe(1));
    const payload = payloads[0];
    expect(payload.executionId).toBe("e1");
    expect(payload.approvalRequestId).toBe("r1");
    expect(payload.action.actionId).toBe("r1");
    expect(payload.action.summary).toBe("Delete file?");
    // The injected payload (incl. clientRequestId) survives in the digest.
    expect(payload.action.digest).toContain('"clientRequestId":"r1"');
    expect(c.pendingCount).toBe(1);

    c.resolve({ clientRequestId: "r1", decision: "APPROVED" });
    const outcome = await pending;
    expect(outcome.approved).toBe(true);
    expect(c.pendingCount).toBe(0);
  });

  it("resolves a rejection with comment", async () => {
    const { sender } = recorder();
    const c = new ApprovalCoordinator({ sender, generateId: fixedIds("r1") });

    const pending = c.requestApproval({ executionId: "e1" });
    c.resolve({
      clientRequestId: "r1",
      decision: "REJECTED",
      comment: "nope",
    });

    const outcome = await pending;
    expect(outcome).toMatchObject({
      decision: "REJECTED",
      rejected: true,
      approved: false,
      comment: "nope",
    });
  });

  it("rejects with ApprovalTimeoutError when no decision arrives", async () => {
    vi.useFakeTimers();
    const { sender } = recorder();
    const c = new ApprovalCoordinator({ sender, generateId: fixedIds("r1") });

    const pending = c.requestApproval({ executionId: "e1", timeoutMs: 1000 });
    const assertion = expect(pending).rejects.toBeInstanceOf(ApprovalTimeoutError);

    await vi.advanceTimersByTimeAsync(1000);
    await assertion;
    expect(c.pendingCount).toBe(0);
    vi.useRealTimers();
  });

  it("ignores a decision with no matching pending request", () => {
    const { sender } = recorder();
    const c = new ApprovalCoordinator({ sender });

    expect(
      c.resolve({ clientRequestId: "ghost", decision: "APPROVED" }),
    ).toBe(false);
  });

  it("ignores a decision missing the client request id", () => {
    const { sender } = recorder();
    const c = new ApprovalCoordinator({ sender });

    expect(c.resolve({ decision: "APPROVED" })).toBe(false);
  });

  it("requires a sender (constructor guard)", () => {
    expect(
      () =>
        new ApprovalCoordinator({
          // @ts-expect-error — missing sender must be rejected at runtime too.
          sender: undefined,
        }),
    ).toThrow("ApprovalCoordinator requires a sender");
  });

  it("cleans up the pending wait when the send itself fails", async () => {
    const sender = {
      sendExecutionApprovalRequested: () =>
        Promise.reject(new Error("socket gone")),
    };
    const c = new ApprovalCoordinator({
      sender,
      generateId: fixedIds("r1"),
    });

    await expect(
      c.requestApproval({ executionId: "e1" }),
    ).rejects.toThrow("socket gone");
    expect(c.pendingCount).toBe(0);
  });
});