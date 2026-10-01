// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { describe, expect, test } from "vitest";
import {
  SourcePushError,
  TargetBranchPusher,
  type GitExec,
} from "./TargetBranchPusher.js";

/** Records every exec call; the scripted results pop in order. */
function makeExec(seams: {
  results: Array<{ ok: true } | { ok: false; stderr: string }>;
  calls?: string[][];
}): GitExec {
  let n = 0;
  return async (args) => {
    seams.calls?.push([...args]);
    const next = seams.results[n++] ?? { ok: true as const };
    if (!next.ok) {
      throw Object.assign(new Error("git push failed"), {
        stderr: next.stderr,
      });
    }
    return { stdout: "", stderr: "" };
  };
}

const noSleep = () => Promise.resolve();

describe("TargetBranchPusher (D10 part 1: push before terminal)", () => {
  test("pushes HEAD to origin refs/heads/<targetBranch> (never a force push)", async () => {
    const calls: string[][] = [];
    const pusher = new TargetBranchPusher({
      exec: makeExec({ results: [{ ok: true }], calls }),
      sleep: noSleep,
    });
    await pusher.push({
      checkoutPath: "C:/ws/tasks/d-1/checkout",
      targetBranch: "myrmec/req-1",
    });

    expect(calls).toHaveLength(1);
    expect(calls[0]).toEqual([
      "push",
      "origin",
      "HEAD:refs/heads/myrmec/req-1",
    ]);
  });

  test("passes the credential through the per-invocation credential helper", async () => {
    const calls: string[][] = [];
    const pusher = new TargetBranchPusher({
      exec: makeExec({ results: [{ ok: true }], calls }),
      sleep: noSleep,
    });
    await pusher.push({
      checkoutPath: "/ws/checkout",
      targetBranch: "feat/x",
      accessToken: "tok-1",
    });

    expect(calls[0]?.[0]).toBe("-c");
    expect(calls[0]?.[1]).toContain("credential.helper");
    expect(calls[0]?.[1]).toContain("tok-1");
    expect(calls[0]?.slice(2)).toEqual(["push", "origin", "HEAD:refs/heads/feat/x"]);
  });

  test("retries transient failures up to 3 attempts with 1s backoff", async () => {
    const calls: string[][] = [];
    const sleeps: number[] = [];
    const pusher = new TargetBranchPusher({
      exec: makeExec({
        results: [
          { ok: false, stderr: "transient network error" },
          { ok: false, stderr: "still flaky" },
          { ok: true },
        ],
        calls,
      }),
      sleep: async (ms) => {
        sleeps.push(ms);
      },
    });
    await pusher.push({ checkoutPath: "/ws/checkout", targetBranch: "feat/x" });

    expect(calls).toHaveLength(3);
    expect(sleeps).toEqual([1000, 1000]);
  });

  test("throws SourcePushError with the LAST stderr line after 3 failed attempts", async () => {
    const calls: string[][] = [];
    const sleeps: number[] = [];
    const pusher = new TargetBranchPusher({
      exec: makeExec({
        results: [
          { ok: false, stderr: "first failure\nsecond failure\nlast line wins" },
          { ok: false, stderr: "boom A\nboom B" },
          { ok: false, stderr: "final: rejected" },
        ],
        calls,
      }),
      sleep: async (ms) => {
        sleeps.push(ms);
      },
    });

    let caught: unknown;
    try {
      await pusher.push({ checkoutPath: "/ws/checkout", targetBranch: "feat/x" });
    } catch (err) {
      caught = err;
    }

    expect(caught).toBeInstanceOf(SourcePushError);
    expect((caught as Error).message).toBe("final: rejected");
    expect(calls).toHaveLength(3);
    expect(sleeps).toEqual([1000, 1000]);
  });

  test("redacts credential-bearing URLs from the surfaced stderr", async () => {
    const pusher = new TargetBranchPusher({
      exec: makeExec({
        results: [
          { ok: false, stderr: "x" },
          { ok: false, stderr: "x" },
          { ok: false, stderr: "remote: https://user:secret@host/repo.git denied" },
        ],
      }),
      sleep: noSleep,
    });
    let caught: unknown;
    try {
      await pusher.push({ checkoutPath: "/ws/checkout", targetBranch: "feat/x" });
    } catch (err) {
      caught = err;
    }
    expect((caught as Error).message).toBe(
      "remote: https://***@host/repo.git denied",
    );
  });
});