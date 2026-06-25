// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { describe, it, expect, vi } from "vitest";
import {
  ApprovalCoordinator,
  ApprovalTimeoutError,
} from "./ApprovalCoordinator.js";
import type { Envelope } from "../protocol/envelope.js";
import { MessageType } from "../protocol/messages.js";

function recorder() {
  const frames: Envelope[] = [];
  const send = (frame: Envelope) => {
    frames.push(frame);
    return Promise.resolve();
  };
  return { frames, send };
}

/** A deterministic id generator so tests can correlate request/decision. */
function fixedIds(...ids: string[]) {
  let i = 0;
  return () => ids[i++] ?? `extra_${i}`;
}

describe("ApprovalCoordinator", () => {
  it("emits an approval.request stamping the client request id", async () => {
    const { frames, send } = recorder();
    const c = new ApprovalCoordinator({ send, generateId: fixedIds("r1") });

    const pending = c.requestApproval({
      conversationId: "c1",
      content: "Delete file?",
      payload: { path: "/tmp/x" },
    });

    // The request frame is sent synchronously before the await resolves.
    await vi.waitFor(() => expect(frames.length).toBe(1));
    const frame = frames[0];
    expect(frame.type).toBe(MessageType.APPROVAL_REQUEST);
    expect(frame.payload).toMatchObject({
      conversationId: "c1",
      clientRequestId: "r1",
      content: "Delete file?",
    });
    const body = JSON.parse(
      (frame.payload as { payloadJson: string }).payloadJson,
    );
    expect(body).toEqual({ path: "/tmp/x", clientRequestId: "r1" });
    expect(c.pendingCount).toBe(1);

    c.resolve({ conversationId: "c1", clientRequestId: "r1", decision: "APPROVED" });
    const outcome = await pending;
    expect(outcome.approved).toBe(true);
    expect(c.pendingCount).toBe(0);
  });

  it("resolves a rejection with comment", async () => {
    const { send } = recorder();
    const c = new ApprovalCoordinator({ send, generateId: fixedIds("r1") });

    const pending = c.requestApproval({ conversationId: "c1" });
    c.resolve({
      conversationId: "c1",
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
    const { send } = recorder();
    const c = new ApprovalCoordinator({ send, generateId: fixedIds("r1") });

    const pending = c.requestApproval({ conversationId: "c1", timeoutMs: 1000 });
    const assertion = expect(pending).rejects.toBeInstanceOf(ApprovalTimeoutError);

    await vi.advanceTimersByTimeAsync(1000);
    await assertion;
    expect(c.pendingCount).toBe(0);
    vi.useRealTimers();
  });

  it("ignores a decision with no matching pending request", () => {
    const { send } = recorder();
    const c = new ApprovalCoordinator({ send });

    expect(
      c.resolve({ conversationId: "c1", clientRequestId: "ghost", decision: "APPROVED" }),
    ).toBe(false);
  });

  it("ignores a decision missing the client request id", () => {
    const { send } = recorder();
    const c = new ApprovalCoordinator({ send });

    expect(
      c.resolve({ conversationId: "c1", decision: "APPROVED" }),
    ).toBe(false);
  });
});
