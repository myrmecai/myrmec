// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * AttemptModelScheduler tests (design 14.5): ONE attempt-level model-call
 * scheduler - one permit at a time, the pending INTERACTION invocation
 * preempts queued ORCHESTRATION work, the permit frees in finally, queued
 * work rejects on stop(), and every ORCHESTRATION acquisition rechecks the
 * hold gate: a queued call must never hold the permit while parked at the
 * gate (otherwise it would starve the admitted interaction indefinitely).
 *
 * The gate here is a STUB implementing the scheduler's gate interface -
 * never the real coordinator. No real sleeps: blocked promises only.
 */

import { describe, expect, test } from "vitest";
import {
  AttemptModelScheduler,
  type AttemptSchedulerGate,
} from "./AttemptModelScheduler.js";
import {
  HoldAbortedError,
  type SafePoint,
} from "./ExecutionControlCoordinator.js";

// ---- helpers ---------------------------------------------------------

/** Deterministically settle promise chains built on microtasks. */
async function flush(): Promise<void> {
  for (let i = 0; i < 25; i++) await Promise.resolve();
}

/** A promise the test releases by hand (never a real timer). */
function blockedPromise<T>(): { promise: Promise<T>; release(value: T): void } {
  let release!: (value: T) => void;
  const promise = new Promise<T>((resolve) => {
    release = resolve;
  });
  return { promise, release };
}

const ALL_SAFE_POINTS: readonly SafePoint[] = [
  "BEFORE_MODEL_CALL",
  "BEFORE_TOOL_EXECUTION",
  "BEFORE_HELPER_CALL",
  "BEFORE_GIT_EFFECT",
];

/** Stub hold gate: enterOperation parks while held; wakes on continue.
 * MIMICS the real coordinator's wake contract (22.5): a parked waiter
 * wakes with a REAL lease whose release() decrements the active-leaf
 * counter (the coordinator increments it AT WAKE, not at park) - a
 * caller that drops the woken lease leaks a leaf forever. */
class StubGate implements AttemptSchedulerGate {
  state: "RUNNING" | "HOLD_REQUESTED" | "HELD" = "RUNNING";
  readonly points: SafePoint[] = [];
  /** snapshot() consults (the waking re-admission rechecks here). */
  snapshotConsults = 0;
  /** The coordinator's active-leaf surrogate (woken parkers included). */
  activeLeaves = 0;
  private readonly parkers: Array<{
    resolve(lease: { release(): void }): void;
    reject(error: unknown): void;
  }> = [];

  snapshot(): Readonly<{ effectiveState: "RUNNING" | "HOLD_REQUESTED" | "HELD" }> {
    this.snapshotConsults += 1;
    return { effectiveState: this.state };
  }

  enterOperation(point: SafePoint): Promise<{ release(): void }> {
    this.points.push(point);
    if (this.state === "RUNNING") {
      this.activeLeaves += 1;
      return Promise.resolve({
        release: () => {
          this.activeLeaves -= 1;
        },
      });
    }
    return new Promise((resolve, reject) => {
      this.parkers.push({ resolve, reject });
    });
  }

  /** Withdraw the hold: every parked waiter wakes with a REAL lease -
   * the active-leaf count increments AT WAKE (the real
   * wakeAllParkers("CONTINUE") contract), so the woken caller's release
   * is its own duty. */
  continueAll(): void {
    this.state = "RUNNING";
    for (const p of this.parkers.splice(0, this.parkers.length)) {
      this.activeLeaves += 1;
      p.resolve({
        release: () => {
          this.activeLeaves -= 1;
        },
      });
    }
  }
}

void ALL_SAFE_POINTS;

describe("AttemptModelScheduler (design 14.5)", () => {
  test("grants ONE model invocation at a time; the next queued call waits for the release", async () => {
    const scheduler = new AttemptModelScheduler();
    const firstWork = blockedPromise<string>();
    const started: string[] = [];
    const first = scheduler.invoke("ORCHESTRATION", async () => {
      started.push("first-ran");
      return await firstWork.promise;
    });
    await flush();
    expect(started).toEqual(["first-ran"]);

    const second = scheduler.invoke("ORCHESTRATION", async () => {
      started.push("second-ran");
      return "second";
    });
    await flush();
    expect(started).toEqual(["first-ran"]); // parked on the permit

    firstWork.release("first");
    expect(await first).toBe("first");
    await flush();
    expect(started).toEqual(["first-ran", "second-ran"]);
    expect(await second).toBe("second");
  });

  test("a pending INTERACTION invocation preempts queued ORCHESTRATION at the next release", async () => {
    const scheduler = new AttemptModelScheduler();
    const firstWork = blockedPromise<string>();
    const order: string[] = [];
    const first = scheduler.invoke("ORCHESTRATION", async () => {
      order.push("orch1");
      return await firstWork.promise;
    });
    await flush();

    const queuedOrch = scheduler.invoke("ORCHESTRATION", async () => {
      order.push("orch2");
      return "orch2";
    });
    const interaction = scheduler.invoke("INTERACTION", async () => {
      order.push("interaction");
      return "interaction";
    });
    await flush();
    expect(order).toEqual(["orch1"]); // the interaction is queued

    firstWork.release("orch1");
    expect(await first).toBe("orch1");
    await flush();
    // The interaction won the permit over the EARLIER-queued
    // orchestration: it ran (and completed) BEFORE orch2 ever started.
    expect(order).toEqual(["orch1", "interaction", "orch2"]);
    expect(order.indexOf("interaction")).toBeLessThan(order.indexOf("orch2"));
    expect(await interaction).toBe("interaction");
    expect(await queuedOrch).toBe("orch2");
  });

  test("releases the permit in finally: a throwing work frees the permit for the next waiter", async () => {
    const scheduler = new AttemptModelScheduler();
    const first = scheduler.invoke(
      "ORCHESTRATION",
      async () =>
        await Promise.reject(new Error("work exploded")).then(() => "never"),
    );
    await flush();
    const second = scheduler.invoke("ORCHESTRATION", async () => "after-throw");
    await flush();
    await expect(first).rejects.toThrow("work exploded");
    await flush();
    expect(await second).toBe("after-throw");
  });

  test("stop() rejects queued work and refuses new invocations; in-flight work completes and frees its permit", async () => {
    const scheduler = new AttemptModelScheduler();
    const firstWork = blockedPromise<string>();
    const first = scheduler.invoke("ORCHESTRATION", async () =>
      await firstWork.promise,
    );
    await flush();
    const queued = scheduler.invoke("ORCHESTRATION", async () => "never");
    await flush();

    scheduler.stop();
    await expect(queued).rejects.toBeInstanceOf(HoldAbortedError);
    await expect(
      scheduler.invoke("ORCHESTRATION", async () => "never"),
    ).rejects.toBeInstanceOf(HoldAbortedError);

    firstWork.release("drained");
    expect(await first).toBe("drained");
  });

  test("gate recheck: an ORCHESTRATION acquisition while HELD releases the permit, parks, and retries after CONTINUE; INTERACTION runs meanwhile", async () => {
    const gate = new StubGate();
    gate.state = "HELD";
    const scheduler = new AttemptModelScheduler({ control: gate });
    const ran: string[] = [];

    const parkedOrch = scheduler.invoke("ORCHESTRATION", async () => {
      ran.push("orch");
      return "orch";
    });
    await flush();
    expect(gate.points).toContain("BEFORE_MODEL_CALL");
    expect(ran).toEqual([]); // parked at the gate, still held

    // The permit was RELEASED before parking: an interaction call proceeds.
    const interaction = scheduler.invoke("INTERACTION", async () => {
      ran.push("interaction");
      return "interaction";
    });
    await flush();
    expect(ran).toEqual(["interaction"]);
    expect(await interaction).toBe("interaction");

    gate.continueAll();
    await flush();
    expect(ran).toEqual(["interaction", "orch"]);
    expect(await parkedOrch).toBe("orch");
    // The waking admission rechecked the gate via snapshot() (the
    // RUNNING snapshot admits without a second park).
    expect(gate.snapshotConsults).toBeGreaterThanOrEqual(2);
    expect(gate.state).toBe("RUNNING");
  });

  test("INTERACTION work never consults the orchestrator's hold gate", async () => {
    const gate = new StubGate();
    gate.state = "HELD";
    const scheduler = new AttemptModelScheduler({ control: gate });
    const result = await scheduler.invoke("INTERACTION", async () => "chat");
    expect(result).toBe("chat");
    expect(gate.points).toEqual([]);
  });

  test("wake contract: the scheduler RELEASES the woken gate lease - no active-leaf leak across hold/continue cycles", async () => {
    // The gate stub mimics the real coordinator: a parked waiter wakes
    // with a REAL lease whose release() decrements activeLeaves (22.5).
    // The old behavior dropped the woken lease, so activeLeaves leaked
    // +1 per parked wake and a second hold on the same attempt could
    // never drain to HELD.
    const gate = new StubGate();
    const scheduler = new AttemptModelScheduler({ control: gate });

    // Cycle 1: HOLD first, then the parked invocation.
    gate.state = "HELD";
    const parkedOnce = scheduler.invoke("ORCHESTRATION", async () => "first");
    await flush();
    expect(gate.activeLeaves).toBe(0); // parked, not yet an active leaf

    gate.continueAll();
    await flush();
    expect(await parkedOnce).toBe("first");
    // The wakeup lease was released by the scheduler: the leaf counter
    // is back to zero (dropping it would leave it at 1).
    expect(gate.activeLeaves).toBe(0);

    // Cycle 2 on the SAME gate/scheduler pair: a fresh HOLD must still
    // park a new invocation (the leaked leaf from cycle 1 would make the
    // gate unable to drain).
    gate.state = "HELD";
    const ran2 = blockedPromise<string>();
    const parkedTwice = scheduler.invoke("ORCHESTRATION", async () => {
      return await ran2.promise;
    });
    await flush();
    expect(gate.activeLeaves).toBe(0); // parked again, nothing leaked

    gate.continueAll();
    await flush();
    ran2.release("second");
    expect(await parkedTwice).toBe("second");
    expect(gate.activeLeaves).toBe(0); // still zero after two full cycles
  });

  test("the scheduler releases the wakeup lease even when the scheduler was stopped mid-wake", async () => {
    // A CONTINUE waking a parked waiter races a concurrent stop(): the
    // woken lease must still be released before the abort rethrows, so
    // the coordinator's leaf count drains even on the cancel path.
    const gate = new StubGate();
    gate.state = "HELD";
    const scheduler = new AttemptModelScheduler({ control: gate });
    const parked = scheduler.invoke("ORCHESTRATION", async () => "never");
    await flush();

    gate.continueAll(); // hands out the lease...
    scheduler.stop(); // ...but the scheduler was stopped while waking

    await expect(parked).rejects.toBeInstanceOf(HoldAbortedError);
    await flush();
    expect(gate.activeLeaves).toBe(0); // the woken lease was still released
  });
});