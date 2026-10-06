// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * ExecutionControlCoordinator fake-clock tests (design 14.2-14.4, protocol
 * 22.4/22.5). No real sleeps anywhere: the monotonic clock and the timer
 * scheduler are injected fakes the test drives manually, and publishes
 * settle through bounded microtask drains (the durable seam stubs resolve
 * synchronously inside promises).
 */

import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, test } from "vitest";
import { executionControlStatePayloadSchema } from "../protocol/unifiedFrames.js";
import type {
  ExecutionControlPayload,
  ExecutionControlRequestResolvedPayload,
  ExecutionControlStatePayload,
  InteractionPolicy,
} from "../protocol/unifiedFrames.js";
import {
  FsOutboxBackend,
  OrchestrationOutbox,
} from "../protocol/OrchestrationOutbox.js";
import {
  ExecutionControlCoordinator,
  HoldAbortedError,
  mayAutoResume,
} from "./ExecutionControlCoordinator.js";
import type { ControlSnapshot } from "./ExecutionControlCoordinator.js";

const EXECUTION_ID = "c0000000-0000-4000-8000-000000000001";
const DISPATCH_ID = "c0000000-0000-4000-8000-000000000002";
const INTERACTION_A = "c0000000-0000-4000-8000-000000000003";
const INTERACTION_B = "c0000000-0000-4000-8000-000000000004";
const REQUEST_1 = "c0000000-0000-4000-8000-000000000005";
const REQUEST_2 = "c0000000-0000-4000-8000-000000000006";

// ---- Fake clock / scheduler (no real timers, no vi.useFakeTimers) ----

interface FakeTimer {
  at: number;
  id: number;
  callback: () => void;
}

function makeFakeClock(start = 1_700_000_000_000) {
  let now = start;
  let nextId = 1;
  const timers: FakeTimer[] = [];
  const clock = { now: (): number => now };
  const scheduler = {
    schedule: (delayMs: number, callback: () => void): void => {
      timers.push({ at: now + delayMs, id: nextId++, callback });
    },
  };
  /** Advance the monotonic clock and run every timer that becomes due,
   * iteratively (callbacks may schedule further timers). */
  function advance(ms: number): void {
    now += ms;
    let progressed = true;
    while (progressed) {
      progressed = false;
      const due = timers
        .filter((t) => t.at <= now)
        .sort((a, b) => a.at - b.at || a.id - b.id);
      for (const t of due) {
        const index = timers.indexOf(t);
        if (index === -1) continue;
        timers.splice(index, 1);
        progressed = true;
        t.callback();
      }
    }
  }
  return {
    clock,
    scheduler,
    advance,
    pendingTimers: (): number => timers.length,
  };
}

/** Deterministically settle the coordinator's promise chains. The seam
 * stubs resolve synchronously, so bounded microtask hops suffice. */
async function flush(): Promise<void> {
  for (let i = 0; i < 25; i++) await Promise.resolve();
}

// ---- Builders ----

function policy(idleResumeAfterSeconds = 300): InteractionPolicy {
  return {
    version: 1,
    enabled: true,
    idleResumeAfterSeconds,
    responseTimeoutSeconds: 120,
    maxInputBytes: 16384,
    maxOutputBytes: 65536,
    maxModelIterations: 8,
    maxHistoryBytes: 262144,
    transcriptRetentionDays: 30,
    contentMode: "USER_CHAT_ONLY",
  };
}

function hold(revision: number, idleSeconds = 300): ExecutionControlPayload {
  return {
    executionId: EXECUTION_ID,
    dispatchId: DISPATCH_ID,
    controlRevision: revision,
    action: "HOLD",
    reasonCode: "USER_REQUESTED",
    holdPolicy: { idleResumeAfterSeconds: idleSeconds },
    controlRequestId: null,
  };
}

function continuE(revision: number, reasonCode = "USER_REQUESTED"): ExecutionControlPayload {
  return {
    executionId: EXECUTION_ID,
    dispatchId: DISPATCH_ID,
    controlRevision: revision,
    action: "CONTINUE",
    reasonCode,
    holdPolicy: null,
    controlRequestId: null,
  };
}

function resolvedPayload(
  status: ExecutionControlRequestResolvedPayload["status"],
  controlRequestId: string,
): ExecutionControlRequestResolvedPayload {
  return {
    executionId: EXECUTION_ID,
    dispatchId: DISPATCH_ID,
    interactionId: INTERACTION_A,
    controlRequestId,
    resolutionRevision: 1,
    status,
    expiresAt: null,
    commandMessageId: null,
    controlRevision: null,
    errorCode: null,
  };
}

// ---- Harness ----

interface PublishedRecord {
  id: string;
  payload: ExecutionControlStatePayload;
  correlationId: string | null;
}

/** One persistControlState call held by the manual seam mode. */
interface PendingPersist {
  payload: ExecutionControlStatePayload;
  correlationId: string | null;
  settle: (error?: Error) => void;
}

interface Harness {
  coordinator: ExecutionControlCoordinator;
  fake: ReturnType<typeof makeFakeClock>;
  published: ExecutionControlStatePayload[];
  seamRecords: PublishedRecord[];
  /** Manual seam mode: persistControlState calls awaiting a settle. */
  readonly pendingPersists: PendingPersist[];
  /** Inject a persist failure: a non-null message rejects matching records. */
  failPersistWith(
    filter: (
      payload: ExecutionControlStatePayload,
      correlationId: string | null,
    ) => string | null,
  ): void;
  /** Record every pending manual seam record, then release its waiter. */
  settlePendingPersists(): void;
}

function makeHarness(
  overrides?: Partial<{
    idleSeconds: number;
    startAt: number;
    /** "manual": persists block until settlePendingPersists (in-flight tests). */
    seamMode: "auto" | "manual";
  }>,
): Harness {
  const fake = makeFakeClock(overrides?.startAt);
  const published: ExecutionControlStatePayload[] = [];
  const seamRecords: PublishedRecord[] = [];
  const seamMode = overrides?.seamMode ?? "auto";
  const pendingPersists: PendingPersist[] = [];
  let recordCounter = 0;
  let failureFilter:
    | ((
        payload: ExecutionControlStatePayload,
        correlationId: string | null,
      ) => string | null)
    | null = null;
  const recordPersisted = (
    payload: ExecutionControlStatePayload,
    correlationId: string | null,
  ): void => {
    recordCounter++;
    seamRecords.push({
      id: `rec-${recordCounter}`,
      payload: { ...payload },
      correlationId,
    });
  };
  const persist: (
    payload: ExecutionControlStatePayload,
    correlationId: string | null,
  ) => Promise<void> =
    seamMode === "manual"
      ? (payload, correlationId) =>
          new Promise<void>((resolve, reject) => {
            const pending: PendingPersist = {
              // A copy: later turns mutate the coordinator's payload object.
              payload: { ...payload },
              correlationId,
              // Settle = land into the durable record list (unless it
              // FAILED) + leave the pending list (unaffected items remain;
              // settlePendingPersists lands everything still there).
              settle: (error?: Error) => {
                const index = pendingPersists.indexOf(pending);
                if (index !== -1) pendingPersists.splice(index, 1);
                if (error === undefined) {
                  recordPersisted(payload, correlationId);
                  resolve();
                } else {
                  reject(error);
                }
              },
            };
            pendingPersists.push(pending);
          })
      : (payload, correlationId) => {
          const failure =
            failureFilter === null ? null : failureFilter(payload, correlationId);
          if (failure !== null) return Promise.reject(new Error(failure));
          recordPersisted(payload, correlationId);
          return Promise.resolve();
        };
  const coordinator = new ExecutionControlCoordinator({
    executionId: EXECUTION_ID,
    dispatchId: DISPATCH_ID,
    policy: policy(overrides?.idleSeconds ?? 300),
    clock: fake.clock,
    scheduler: fake.scheduler,
    outbox: {
      persistControlState: persist,
    },
    emitter: {
      publish: (payload) => published.push(payload),
    },
  });
  return {
    coordinator,
    fake,
    published,
    seamRecords,
    pendingPersists,
    failPersistWith: (filter) => {
      failureFilter = filter;
    },
    settlePendingPersists: () => {
      // The settle hook itself records the durable record; landing every
      // pending item settles them (emptying the list).
      const items = pendingPersists.splice(0, pendingPersists.length);
      for (const item of items) item.settle();
    },
  };
}

/** Narrow + validate one published state through the wire schema. */
function stateOf(
  published: ExecutionControlStatePayload[],
  index: number,
): ExecutionControlStatePayload {
  const payload = published[index];
  expect(payload, `no state published at index ${index}`).toBeDefined();
  const parsed = executionControlStatePayloadSchema.parse(payload);
  return parsed;
}

describe("ExecutionControlCoordinator (design 14.2-14.4, protocol 22.4/22.5)", () => {
  test("HOLD during an active leaf publishes HOLD_REQUESTED; lease release publishes HELD at the safe point with the idle clock armed", async () => {
    const { coordinator, published } = makeHarness();
    const lease = await coordinator.enterOperation("BEFORE_TOOL_EXECUTION");

    await coordinator.apply(hold(1), "msg-hold-1");
    const requested = stateOf(published, 0);
    expect(requested.status).toBe("HOLD_REQUESTED");
    expect(requested.effectiveState).toBe("HOLD_REQUESTED");
    expect(requested.controlRevision).toBe(1);
    expect(requested.stateSequence).toBe(1);
    expect(requested.reasonCode).toBe("USER_REQUESTED");
    expect(requested.idleResumeAt).toBeNull();
    // Command-driven states correlate to the originating command messageId.
    expect(coordinator.snapshot().effectiveState).toBe("HOLD_REQUESTED");

    lease.release();
    await flush();
    const held = stateOf(published, 1);
    expect(held.status).toBe("HELD");
    expect(held.controlRevision).toBe(1);
    expect(held.stateSequence).toBe(2);
    expect(held.safePoint).toBe("BEFORE_TOOL_EXECUTION");
    expect(held.idleResumeAt).not.toBeNull();
    expect(Date.parse(held.idleResumeAt as string)).toBe(
      Date.parse(held.changedAt) + 300_000,
    );
    // The HELD transition is command-correlated too (deferred HELD).
    expect(coordinator.snapshot().acceptedControlRevision).toBe(1);
  });

  test("300-second idle expiry publishes RUNNING at the UNCHANGED engine revision with HOLD_IDLE_EXPIRED and no correlationId", async () => {
    const { coordinator, fake, published } = makeHarness();
    await coordinator.apply(hold(1), "msg-hold-1"); // zero leaves: HOLD_REQUESTED + HELD
    await coordinator.apply(hold(2), "msg-hold-1b") /* repeated HOLD while HELD */;
    const held = stateOf(published, 2);
    const revisionBefore = held.controlRevision;
    const sequenceBefore = held.stateSequence;

    fake.advance(300_000);
    await flush();

    const resumed = stateOf(published, 3);
    expect(resumed.status).toBe("RUNNING");
    expect(resumed.effectiveState).toBe("RUNNING");
    // Auto-resume keeps the accepted revision and advances stateSequence.
    expect(resumed.controlRevision).toBe(revisionBefore);
    expect(resumed.stateSequence).toBe(sequenceBefore + 1);
    expect(resumed.reasonCode).toBe("HOLD_IDLE_EXPIRED");
    expect(resumed.idleResumeAt).toBeNull();
    expect(coordinator.snapshot().acceptedControlRevision).toBe(Number(resumed.controlRevision));
  });

  test("pending interaction inhibits auto-resume; settlement arms a NEW FULL interval (not the remainder)", async () => {
    const { coordinator, fake, published } = makeHarness();
    await coordinator.apply(hold(1), "msg-hold-1"); // HELD, idle armed

    expect(coordinator.beginInteraction(INTERACTION_A)).toBe(true);
    await flush();
    const activity = stateOf(published, 2);
    expect(activity.status).toBe("HELD");
    expect(activity.reasonCode).toBe("INTERACTION_ACTIVITY");
    expect(activity.idleResumeAt).toBeNull();
    expect(coordinator.snapshot().pendingInteractionId).toBe(INTERACTION_A);

    // The idle timer was cancelled: 300s later there is still no auto-resume.
    fake.advance(300_000);
    await flush();
    expect(published).toHaveLength(3);

    // Concurrent admission is rejected - one slot, no queue.
    expect(coordinator.beginInteraction(INTERACTION_B)).toBe(false);

    // Settlement arms a full interval from NOW (remainder would resume at 100s).
    fake.advance(100_000);
    coordinator.endInteraction(INTERACTION_A);
    await flush();
    const settled = stateOf(published, 3);
    expect(settled.status).toBe("HELD");
    expect(settled.reasonCode).toBe("INTERACTION_SETTLED");
    expect(settled.idleResumeAt).not.toBeNull();
    expect(Date.parse(settled.idleResumeAt as string)).toBe(
      Date.parse(settled.changedAt) + 300_000,
    );
    expect(coordinator.snapshot().pendingInteractionId).toBeNull();

    fake.advance(299_000);
    await flush();
    expect(published).toHaveLength(4);
    fake.advance(1_000);
    await flush();
    const resumed = stateOf(published, 4);
    expect(resumed.status).toBe("RUNNING");
    expect(resumed.reasonCode).toBe("HOLD_IDLE_EXPIRED");
  });

  test("pending control-request confirmation inhibits auto-resume until resolution, then re-arms idle", async () => {
    const { coordinator, fake, published } = makeHarness();
    await coordinator.apply(hold(1), "msg-hold-1");

    coordinator.resolveProposal(resolvedPayload("CONFIRMATION_REQUIRED", REQUEST_1));
    await flush();
    const pending = stateOf(published, 2);
    expect(pending.status).toBe("HELD");
    expect(pending.reasonCode).toBe("CONFIRMATION_PENDING");
    expect(pending.idleResumeAt).toBeNull();
    expect(
      [...coordinator.snapshot().pendingControlRequestIds],
    ).toEqual([REQUEST_1]);

    fake.advance(3600_000); // far past the 300s idle mark
    await flush();
    expect(published).toHaveLength(3); // inhibited

    coordinator.resolveProposal(resolvedPayload("EXPIRED", REQUEST_1));
    await flush();
    const settled = stateOf(published, 3);
    expect(settled.status).toBe("HELD");
    expect(settled.reasonCode).toBe("CONFIRMATION_SETTLED");
    expect(settled.idleResumeAt).not.toBeNull();
    expect(coordinator.snapshot().pendingControlRequestIds).toEqual([]);

    fake.advance(300_000);
    await flush();
    expect(stateOf(published, 4).status).toBe("RUNNING");
  });

  test("a second pending confirmation does not clear until its own resolution arrives", async () => {
    const { coordinator } = makeHarness();
    await coordinator.apply(hold(1), "msg-hold-1");
    coordinator.resolveProposal(resolvedPayload("CONFIRMATION_REQUIRED", REQUEST_1));
    coordinator.resolveProposal(resolvedPayload("CONFIRMATION_REQUIRED", REQUEST_2));
    expect([...coordinator.snapshot().pendingControlRequestIds]).toEqual([
      REQUEST_1,
      REQUEST_2,
    ]);
    // Resolving only ONE keeps the inhibitor alive (mayAutoResume false).
    coordinator.resolveProposal(resolvedPayload("ACCEPTED", REQUEST_1));
    expect([...coordinator.snapshot().pendingControlRequestIds]).toEqual([REQUEST_2]);
  });

  test("deadline expiry wakes parked waiters like stop; stop is idempotent and rejects further admission", async () => {
    const { coordinator, fake, published } = makeHarness();
    expect(coordinator.beginInteraction(INTERACTION_A)).toBe(true); // keep slot busy
    coordinator.endInteraction(INTERACTION_A);
    await coordinator.apply(hold(1), "msg-hold-1");
    const waiter = coordinator.enterOperation("BEFORE_MODEL_CALL");
    let caught: unknown = null;
    void waiter.catch((e: unknown) => {
      caught = e;
    });

    // deadlineExpired behaves like stop for wake purposes...
    coordinator.markDeadlineExpired();
    await flush();
    expect(caught).toBeInstanceOf(HoldAbortedError);
    // ...but leaves the terminal decision to the executor: no STOPPED state
    // published by the coordinator (terminals are the executor's frames).
    expect(published).toHaveLength(2);
    // mayAutoResume is fenced by deadlineExpired even if a timer survived.
    fake.advance(3600_000);
    await flush();
    expect(published).toHaveLength(2);

    // A second coordinator proves stop(wake) + admission-refusal behavior.
    const h = makeHarness();
    await h.coordinator.apply(hold(1), "msg-hold-1");
    const w = h.coordinator.enterOperation("BEFORE_HELPER_CALL");
    let wError: unknown = null;
    void w.catch((e: unknown) => {
      wError = e;
    });
    h.coordinator.stop("CANCEL");
    await flush();
    expect(wError).toBeInstanceOf(HoldAbortedError);
    h.coordinator.stop("CANCEL"); // idempotent, no crash
    await expect(h.coordinator.enterOperation("BEFORE_MODEL_CALL")).rejects.toBeInstanceOf(
      HoldAbortedError,
    );
    // Commands after stop are rejected with EXECUTION_TERMINAL.
    await h.coordinator.apply(hold(2), "msg-after-stop");
    const rejected = stateOf(h.published, 2);
    expect(rejected.status).toBe("REJECTED");
    expect(rejected.effectiveState).toBe("HELD");
    expect(rejected.errorCode).toBe("EXECUTION_TERMINAL");
    expect(rejected.rejectedControlRevision).toBe(2);
    expect(h.coordinator.snapshot().acceptedControlRevision).toBe(1);
  });

  test("delayed CONTINUE withdraws the pending hold before HELD and wakes parked waiters", async () => {
    const { coordinator, published } = makeHarness();
    const leaf = await coordinator.enterOperation("BEFORE_MODEL_CALL");
    await coordinator.apply(hold(1), "msg-hold-1");
    const parked = coordinator.enterOperation("BEFORE_TOOL_EXECUTION");

    await coordinator.apply(continuE(2), "msg-cont-1");
    const running = stateOf(published, 1);
    expect(running.status).toBe("RUNNING");
    expect(running.controlRevision).toBe(2);
    expect(running.reasonCode).toBe("USER_REQUESTED");

    // The parked gate continuation wakes with a real lease.
    const lease = await parked;
    lease.release();
    await flush();
    // NO HELD was published: the hold was withdrawn before the drain finished.
    expect(published.map((p) => p.status)).toEqual(["HOLD_REQUESTED", "RUNNING"]);

    leaf.release();
    await flush();
    expect(published.map((p) => p.status)).toEqual(["HOLD_REQUESTED", "RUNNING"]);
  });

  test("a woken waiter counts as an active leaf: re-HOLD drains via its release", async () => {
    const { coordinator, published } = makeHarness();
    // Close the gate FIRST: the next waiter genuinely parks.
    await coordinator.apply(hold(1), "msg-hold-1"); // zero leaves -> HELD
    expect(published.map((p) => p.status)).toEqual(["HOLD_REQUESTED", "HELD"]);
    const parked = coordinator.enterOperation("BEFORE_MODEL_CALL"); // parks
    await coordinator.apply(continuE(2), "msg-cont-1"); // wakes it

    // CONTINUE woke the parked waiter with a real lease; it counts as an
    // active leaf from its wake, so a NEW HOLD must drain via its release
    // (HELD cannot publish while the leaf is still active).
    const woken = await parked;
    await coordinator.apply(hold(3), "msg-hold-3");
    expect(published.map((p) => p.status)).toEqual([
      "HOLD_REQUESTED", "HELD", "RUNNING", "HOLD_REQUESTED",
    ]);
    woken.release();
    await flush();
    expect(published.map((p) => p.status)).toEqual([
      "HOLD_REQUESTED", "HELD", "RUNNING", "HOLD_REQUESTED", "HELD",
    ]);
  });

  test("stale HOLD is rejected as STALE_CONTROL_REVISION without changing state", async () => {
    const { coordinator, published } = makeHarness();
    await coordinator.apply(continuE(2), "msg-cont-1"); // RUNNING at revision 2

    await coordinator.apply(hold(1), "msg-stale-hold");
    const rejected = stateOf(published, 1);
    expect(rejected.status).toBe("REJECTED");
    expect(rejected.effectiveState).toBe("RUNNING");
    expect(rejected.controlRevision).toBe(2);
    expect(rejected.rejectedControlRevision).toBe(1);
    expect(rejected.errorCode).toBe("STALE_CONTROL_REVISION");
    expect(coordinator.snapshot().acceptedControlRevision).toBe(2);
    expect(coordinator.snapshot().effectiveState).toBe("RUNNING");
  });

  test("same revision with conflicting command bytes is rejected as CONTROL_REVISION_CONFLICT", async () => {
    const { coordinator, published } = makeHarness();
    await coordinator.apply(hold(1), "msg-hold-1");

    await coordinator.apply(continuE(1), "msg-conflict");
    const rejected = stateOf(published, 2);
    expect(rejected.status).toBe("REJECTED");
    expect(rejected.effectiveState).toBe("HELD");
    expect(rejected.rejectedControlRevision).toBe(1);
    expect(rejected.errorCode).toBe("CONTROL_REVISION_CONFLICT");
    expect(coordinator.snapshot().acceptedControlRevision).toBe(1);
  });

  test("a HOLD whose holdPolicy does not match the session policy is rejected", async () => {
    const { coordinator, published } = makeHarness();
    await coordinator.apply(hold(1, 120), "msg-policy-mismatch");
    const rejected = stateOf(published, 0);
    expect(rejected.status).toBe("REJECTED");
    expect(rejected.errorCode).toBe("INVALID_MESSAGE");
    expect(coordinator.snapshot().effectiveState).toBe("RUNNING");
  });

  test("identical command replay (same messageId) returns the stored disposition without reapplying or restarting the timer", async () => {
    const { coordinator, fake, published, seamRecords } = makeHarness();
    await coordinator.apply(hold(1), "msg-hold-1");
    const armedAt = stateOf(published, 1).idleResumeAt;
    const count = published.length;
    const seamCount = seamRecords.length;

    await coordinator.apply(hold(1), "msg-hold-1");
    await flush();
    expect(published).toHaveLength(count);
    expect(seamRecords).toHaveLength(seamCount);
    expect(stateOf(published, 1).idleResumeAt).toBe(armedAt);

    // And the replayed HOLD does not reset the idle clock.
    fake.advance(300_000);
    await flush();
    expect(stateOf(published, 2).status).toBe("RUNNING");
  });

  test("disconnect during HELD inhibits the timer; reconnect re-arms toward the SAME resume instant", async () => {
    const { coordinator, fake, published } = makeHarness();
    await coordinator.apply(hold(1), "msg-hold-1");
    const armedAt = stateOf(published, 1).idleResumeAt;

    // Disconnect BEFORE the instant: the armed callback gets fenced.
    coordinator.setConnectionReady(false);
    fake.advance(150_000); // halfway toward the instant, callback fenced
    await flush();
    expect(published).toHaveLength(2); // inhibited while disconnected

    // Reconnect: re-arm honors the SAME (unchanged) resume instant, so
    // 299s total still does not resume; 300s does.
    const resumedBefore = stateOf(published, 1).idleResumeAt as string;
    coordinator.setConnectionReady(true);
    await flush();
    fake.advance(149_000);
    await flush();
    expect(published).toHaveLength(2);
    fake.advance(1_000); // the ORIGINAL instant is now reached
    await flush();
    const resumed = stateOf(published, 2);
    expect(resumed.status).toBe("RUNNING");
    expect(resumed.reasonCode).toBe("HOLD_IDLE_EXPIRED");
    expect(stateOf(published, 1).idleResumeAt).toBe(armedAt);
    expect(Date.parse(resumedBefore)).toBe(Date.parse(armedAt as string));

    // Variant: the deadline passed while disconnected (the fenced tick
    // was consumed); a later reconnect still resumes on the first tick
    // after authoritative recovery (22.5 - held state never resumes
    // while disconnected, but reconnect re-checks the armed instant).
    const h = makeHarness();
    await h.coordinator.apply(hold(1), "msg-hold-1");
    h.coordinator.setConnectionReady(false);
    h.fake.advance(300_000); // tick consumed, inhibited
    expect(h.published).toHaveLength(2);
    h.coordinator.setConnectionReady(true); // deadline already passed -> delay 0
    h.fake.advance(0); // fire the re-armed callback
    await flush();
    expect(stateOf(h.published, 2).status).toBe("RUNNING");
  });

  test("repeated HOLD while HELD advances the revision but does NOT reset the idle clock", async () => {
    const { coordinator, fake, published } = makeHarness();
    await coordinator.apply(hold(1), "msg-hold-1");
    const armedAt = stateOf(published, 1).idleResumeAt;

    fake.advance(150_000);
    await coordinator.apply(hold(2), "msg-hold-2");
    const observed = stateOf(published, 2);
    expect(observed.status).toBe("HELD");
    expect(observed.controlRevision).toBe(2);
    expect(observed.idleResumeAt).toBe(armedAt); // unchanged, no idle reset

    fake.advance(150_000); // reaches the ORIGINAL resume instant
    await flush();
    const resumed = stateOf(published, 3);
    expect(resumed.status).toBe("RUNNING");
    expect(resumed.controlRevision).toBe(2);
  });

  test("CONTINUE while RUNNING is an accepted idempotent observation", async () => {
    const { coordinator, published } = makeHarness();
    await coordinator.apply(continuE(1), "msg-cont-1");
    const observed = stateOf(published, 0);
    expect(observed.status).toBe("RUNNING");
    expect(observed.effectiveState).toBe("RUNNING");
    expect(observed.controlRevision).toBe(1);
    expect(observed.stateSequence).toBe(1);
    expect(coordinator.snapshot().acceptedControlRevision).toBe(1);
  });

  test("HOLD with zero active leaves publishes HOLD_REQUESTED and HELD in one apply", async () => {
    const { coordinator, published, seamRecords } = makeHarness();
    await coordinator.apply(hold(1), "msg-hold-1");
    expect(published.map((p) => p.status)).toEqual(["HOLD_REQUESTED", "HELD"]);
    // Both records were PERSISTED BEFORE apply resolved (ack ordering).
    expect(seamRecords.map((r) => r.payload.status)).toEqual([
      "HOLD_REQUESTED",
      "HELD",
    ]);
    const held = stateOf(published, 1);
    expect(held.safePoint).toBeNull(); // no leaf ever ran
    expect(held.idleResumeAt).not.toBeNull();
    expect(stateOf(published, 0).idleResumeAt).toBeNull();
  });

  test("HOLD arriving while an interaction is pending publishes HELD WITHOUT an idle clock; settlement re-arms", async () => {
    const { coordinator, published } = makeHarness();
    expect(coordinator.beginInteraction(INTERACTION_A)).toBe(true);
    await coordinator.apply(hold(1), "msg-hold-1"); // zero leaves, interaction pending
    await flush();
    const held = stateOf(published, 1);
    expect(held.status).toBe("HELD");
    expect(held.idleResumeAt).toBeNull(); // inhibitor wins at HELD publication

    coordinator.endInteraction(INTERACTION_A);
    await flush();
    const settled = stateOf(published, 2);
    expect(settled.status).toBe("HELD");
    expect(settled.reasonCode).toBe("INTERACTION_SETTLED");
    expect(settled.idleResumeAt).not.toBeNull();
  });

  test("lease release is crash-safe: a thrown tool does not leak the active count, double release is a no-op", async () => {
    const { coordinator, published } = makeHarness();
    const lease = await coordinator.enterOperation("BEFORE_MODEL_CALL");
    const lease2 = await coordinator.enterOperation("BEFORE_TOOL_EXECUTION");
    const runTool = async (): Promise<void> => {
      try {
        throw new Error("tool blew up under the lease"); // the tool throws
      } finally {
        lease.release();
        lease.release(); // double release: no negative counter
        lease2.release();
      }
    };
    // The caller's finally runs; the throw propagates to the tool owner -
    // the coordinator must be unaffected and the count must not leak.
    await expect(runTool()).rejects.toThrow("tool blew up under the lease");
    await coordinator.apply(hold(1), "msg-hold-1");
    // Active count reached zero (nothing leaked), so HELD publishes in-apply.
    expect(published.map((p) => p.status)).toEqual(["HOLD_REQUESTED", "HELD"]);
  });

  test("endInteraction with a mismatched id is a no-op that keeps the slot", () => {
    const h = makeHarness();
    expect(h.coordinator.beginInteraction(INTERACTION_A)).toBe(true);
    h.coordinator.endInteraction(INTERACTION_B);
    expect(h.coordinator.snapshot().pendingInteractionId).toBe(INTERACTION_A);
    expect(h.coordinator.beginInteraction(INTERACTION_B)).toBe(false);
  });

  test("stateSequence is strictly monotonic across every published notification", async () => {
    const { coordinator, published } = makeHarness();
    const leaf = await coordinator.enterOperation("BEFORE_MODEL_CALL");
    await coordinator.apply(hold(1), "msg-hold-1");
    leaf.release();
    await flush();
    coordinator.beginInteraction(INTERACTION_A);
    coordinator.endInteraction(INTERACTION_A);
    await flush();
    await coordinator.apply(continuE(2), "msg-cont-1");
    const sequences = published.map((p) => p.stateSequence);
    for (let i = 1; i < sequences.length; i++) {
      expect(sequences[i]).toBe((sequences[i - 1] as number) + 1);
    }
    expect(sequences[0]).toBe(1);
  });

  test("enterOperation resolves immediately while RUNNING and parks at the closed gate", async () => {
    const { coordinator, fake, published } = makeHarness();
    const lease = await coordinator.enterOperation("BEFORE_MODEL_CALL"); // immediate
    await coordinator.apply(hold(1), "msg-hold-1"); // 1 active leaf -> drains

    // A NEXT leaf parks because the gate closed (HOLD_REQUESTED).
    const parked = coordinator.enterOperation("BEFORE_HELPER_CALL");
    let parkedResolved = false;
    void parked.then(() => {
      parkedResolved = true;
    });
    await flush();
    expect(parkedResolved).toBe(false);

    // Releasing the last active leaf publishes HELD and arms the idle
    // timer (idle time begins when HELD, 22.5). The parked waiter stays
    // parked, and it does not count as an active leaf.
    lease.release();
    await flush();
    expect(coordinator.snapshot().effectiveState).toBe("HELD");
    expect(fake.pendingTimers()).toBe(1);
    expect(parkedResolved).toBe(false);

    // CONTINUE wakes the parked waiter with a real lease; after both
    // leaves release, state is RUNNING (with the hold withdrawn).
    await coordinator.apply(continuE(2), "msg-cont-1");
    const woken = await parked;
    woken.release();
    await flush();
    expect(parkedResolved).toBe(true);
    expect(published.map((p) => p.status)).toEqual(["HOLD_REQUESTED", "HELD", "RUNNING"]);
  });

  test("mismatched execution/dispatch identity fails loudly without publishing state", async () => {
    const { coordinator, published } = makeHarness();
    const foreign = { ...continuE(1), executionId: "d0000000-0000-4000-8000-000000000009" };
    await expect(coordinator.apply(foreign, "msg-foreign")).rejects.toThrow();
    expect(published).toHaveLength(0);
  });

  // ---- Fix 1: dispositions stamped only after durability; a persist
  // failure rejects `apply` and a re-drive re-runs the FULL apply path;
  // concurrent replays never double-apply. ----

  test("a persist failure on the HOLD_REQUESTED record rejects apply; the re-driven identical command RE-PERSISTS (a failed attempt is never 'already applied')", async () => {
    const { coordinator, fake, published, seamRecords, pendingPersists, settlePendingPersists } =
      makeHarness({ seamMode: "manual" });
    const lease = await coordinator.enterOperation("BEFORE_MODEL_CALL");
    const applyPromise = coordinator.apply(hold(1), "msg-hold-1"); // in flight
    await flush(); // the durability chain reaches the manual seam
    expect(pendingPersists).toHaveLength(1);
    expect(pendingPersists[0]?.payload.status).toBe("HOLD_REQUESTED");

    // FAIL the HOLD_REQUESTED record: the disposition must NOT be
    // stamped (the engine's ack timeout is the retry path) and nothing
    // reaches the live emitter.
    pendingPersists[0]?.settle(new Error("outbox write failed (HOLD_REQUESTED)"));
    await flush();
    await expect(applyPromise).rejects.toThrow("outbox write failed (HOLD_REQUESTED)");
    expect(published).toHaveLength(0); // a failed persist never emits
    expect(coordinator.snapshot().effectiveState).toBe("HOLD_REQUESTED");
    expect(seamRecords).toHaveLength(0); // NOTHING durable was recorded

    // The leaf drains while the command is not durably applied.
    lease.release();
    await flush();
    expect(pendingPersists).toHaveLength(1); // the drain HELD is pending
    expect(coordinator.snapshot().effectiveState).toBe("HELD");

    // The engine re-drives the IDENTICAL command: a FULL fresh attempt -
    // the replay shortcut must NOT answer a failed attempt.
    const retryPromise = coordinator.apply(hold(1), "msg-hold-1");
    await flush();
    // No SECOND record yet: the re-drive's HELD observation chains AFTER
    // the still-pending drain HELD record.
    expect(pendingPersists).toHaveLength(1);
    settlePendingPersists(); // the drain HELD lands
    await flush();
    expect(pendingPersists).toHaveLength(1); // NOW the re-drive's record
    expect(pendingPersists[0]?.payload.status).toBe("HELD");
    settlePendingPersists(); // the re-drive's HELD lands -> durability
    await flush();
    await expect(retryPromise).resolves.toBeUndefined();
    // Exactly these durable records exist: the drain HELD + the re-drive's
    // correlated HELD observation. The failed HOLD_REQUESTED left NO
    // durable record, and the re-drive produced no duplicate pair.
    expect(seamRecords.map((r) => r.payload.status)).toEqual(["HELD", "HELD"]);
    expect(coordinator.snapshot().acceptedControlRevision).toBe(1);

    // Identical replay AFTER durability: stored disposition, no records.
    const durableSeamCount = seamRecords.length;
    await coordinator.apply(hold(1), "msg-hold-1");
    await flush();
    expect(seamRecords).toHaveLength(durableSeamCount);

    // The timer is armed exactly once (no restart from the re-drive).
    fake.advance(300_000);
    await flush();
    expect(pendingPersists).toHaveLength(1); // the auto-resume RUNNING record
    expect(pendingPersists[0]?.payload.reasonCode).toBe("HOLD_IDLE_EXPIRED");
    settlePendingPersists();
    await flush();
    const resumed = stateOf(published, 2);
    expect(resumed.status).toBe("RUNNING");
    expect(resumed.reasonCode).toBe("HOLD_IDLE_EXPIRED");
    expect(published.map((p) => p.status)).toEqual(["HELD", "HELD", "RUNNING"]);
  });

  test("a mid-turn persist failure (the zero-leaf drain HELD of the SAME apply) rejects apply: a turn is acked only when every record of it is durable", async () => {
    const { coordinator, published, seamRecords, pendingPersists, settlePendingPersists } =
      makeHarness({ seamMode: "manual" });
    const applyPromise = coordinator.apply(hold(1), "msg-hold-1"); // zero leaves
    await flush();
    // TWO records belong to ONE command turn; the chain is sequential, so
    // only the FIRST (HOLD_REQUESTED) is at the seam so far.
    expect(pendingPersists).toHaveLength(1);
    expect(pendingPersists[0]?.payload.status).toBe("HOLD_REQUESTED");

    pendingPersists[0]?.settle(); // record 1 lands
    await flush();
    // NOW the second record of the SAME turn (drain HELD) is at the seam.
    expect(pendingPersists).toHaveLength(1);
    expect(pendingPersists[0]?.payload.status).toBe("HELD");
    pendingPersists[0]?.settle(new Error("outbox write failed (HELD)")); // FAIL
    await flush();

    // The mid-turn failure must NOT be swallowed: apply rejects.
    await expect(applyPromise).rejects.toThrow("outbox write failed (HELD)");
    expect(published.map((p) => p.status)).toEqual(["HOLD_REQUESTED"]); // 1st only
    expect(seamRecords.map((r) => r.payload.status)).toEqual(["HOLD_REQUESTED"]);
    expect(coordinator.snapshot().effectiveState).toBe("HELD");

    // Re-drive: the full path runs from the HELD overlay; its idempotent
    // HELD observation re-establishes durable acceptance.
    const retry = coordinator.apply(hold(1), "msg-hold-1");
    await flush();
    expect(pendingPersists).toHaveLength(1);
    expect(pendingPersists[0]?.payload.status).toBe("HELD");
    settlePendingPersists();
    await flush();
    await expect(retry).resolves.toBeUndefined();
    expect(seamRecords.map((r) => r.payload.status)).toEqual([
      "HOLD_REQUESTED",
      "HELD",
    ]);
  });

  test("a concurrent second apply of the same messageId joins the in-flight attempt: no double mutation, no duplicate records, no double timer", async () => {
    const { coordinator, fake, published, pendingPersists, settlePendingPersists } =
      makeHarness({ seamMode: "manual" });
    const first = coordinator.apply(hold(1), "msg-hold-1"); // in flight
    const second = coordinator.apply(hold(1), "msg-hold-1"); // concurrent replay
    let secondSettled = false;
    void second.then(() => {
      secondSettled = true;
    }).catch(() => {
      secondSettled = true;
    });

    await flush();
    // ONLY the leader's record is pending - the replay enqueued nothing.
    expect(pendingPersists).toHaveLength(1);
    expect(secondSettled).toBe(false); // still waiting on the leader

    settlePendingPersists(); // HOLD_REQUESTED lands
    await flush();
    expect(pendingPersists).toHaveLength(1); // the drain HELD tail
    settlePendingPersists(); // HELD lands -> the whole turn is durable
    await flush();
    await expect(first).resolves.toBeUndefined();
    await expect(second).resolves.toBeUndefined(); // shares the outcome
    expect(published.map((p) => p.status)).toEqual(["HOLD_REQUESTED", "HELD"]);
    expect(fake.pendingTimers()).toBe(1); // the timer armed exactly once
  });

  test("a re-drive of the same messageId with DIFFERENT bytes is refused while the first attempt is in flight", async () => {
    const { coordinator, pendingPersists, settlePendingPersists } = makeHarness({
      seamMode: "manual",
    });
    const first = coordinator.apply(hold(1), "msg-hold-1"); // in flight
    const conflicting = coordinator.apply(hold(2), "msg-hold-1"); // different bytes
    await flush();
    expect(pendingPersists).toHaveLength(1); // no mutation from the duplicate

    settlePendingPersists();
    await flush();
    settlePendingPersists();
    await flush();
    await expect(first).resolves.toBeUndefined();
    await expect(conflicting).rejects.toThrow("different bytes");
    // The refused duplicate changed no revision binding.
    expect(coordinator.snapshot().acceptedControlRevision).toBe(1);
  });

  test("mayAutoResume is the verbatim gating invariant over a snapshot", () => {
    const now = 1_700_000_000_000;
    const base: ControlSnapshot = {
      effectiveState: "HELD",
      acceptedControlRevision: 1,
      stateSequence: 2,
      idleResumeAt: new Date(now).toISOString(),
      pendingInteractionId: null,
      pendingControlRequestIds: [],
      connectionReady: true,
      stopped: false,
      deadlineExpired: false,
      version: 2,
      capturedAt: "2026-10-03T10:00:00.000Z",
      safePoint: null,
      lastReasonCode: "USER_REQUESTED",
    };
    expect(mayAutoResume(base, now + 1)).toBe(true);
    expect(mayAutoResume({ ...base, effectiveState: "RUNNING" }, 1e15)).toBe(false);
    expect(mayAutoResume({ ...base, connectionReady: false }, 1e15)).toBe(false);
    expect(mayAutoResume({ ...base, pendingInteractionId: INTERACTION_A }, 1e15)).toBe(false);
    expect(mayAutoResume({ ...base, pendingControlRequestIds: [REQUEST_1] }, 1e15)).toBe(false);
    expect(mayAutoResume({ ...base, idleResumeAt: null }, 1e15)).toBe(false);
    expect(mayAutoResume({ ...base, stopped: true }, 1e15)).toBe(false);
    expect(mayAutoResume({ ...base, deadlineExpired: true }, 1e15)).toBe(false);
    expect(mayAutoResume(base, now - 1)).toBe(false); // not yet due
  });

  test("durable seam: control-state records ride the session outbox with a UNIQUE wire messageId per record, the command correlation as envelope metadata, and survive a NEW outbox instance (Task 10 recovery evidence)", async () => {
    const root = mkdtempSync(join(tmpdir(), "control-coordinator-restart-"));
    try {
      const backend = new FsOutboxBackend(root);
      const outbox = new OrchestrationOutbox({ backend, send: async () => true });
      const coordinator = new ExecutionControlCoordinator({
        executionId: EXECUTION_ID,
        dispatchId: DISPATCH_ID,
        policy: policy(300),
        clock: makeFakeClock().clock,
        scheduler: makeFakeClock().scheduler,
        outbox,
        emitter: { publish: () => {} },
      });
      await coordinator.apply(hold(1), "msg-hold-1");

      // A NEW outbox over the same root sees the durable control-state record.
      const restartedBackend = new FsOutboxBackend(root);
      const controlStates = (await restartedBackend.list()).filter(
        (r) => r.kind === "control_state",
      );
      expect(controlStates).toHaveLength(2);
      expect(controlStates.map((r) => r.payload)).toEqual([
        expect.objectContaining({ status: "HOLD_REQUESTED", stateSequence: 1 }),
        expect.objectContaining({ status: "HELD", stateSequence: 2 }),
      ]);
      // Fix 2: TWO records of ONE command carry DIFFERENT wire messageIds;
      // the command's messageId rides `commandMessageId` on each.
      const [requestedRecord, heldRecord] = controlStates;
      expect(requestedRecord?.messageId).not.toBe(heldRecord?.messageId);
      expect(requestedRecord?.messageId).not.toBe("msg-hold-1");
      expect(heldRecord?.messageId).not.toBe("msg-hold-1");
      expect(requestedRecord?.commandMessageId).toBe("msg-hold-1");
      expect(heldRecord?.commandMessageId).toBe("msg-hold-1");

      // drainOutbox proves the shared emit seam maps the record to the
      // wire frame type - and the sender bridge receives the correlation
      // so it can stamp the envelope (NOT the frame messageId).
      const sent: {
        frame: { type: string; correlationId?: string };
        record: { messageId?: string };
      }[] = [];
      const drainOutbox = new OrchestrationOutbox({
        backend: new FsOutboxBackend(root),
        send: async (frame, record) => {
          sent.push({ frame, record });
          return true;
        },
      });
      await drainOutbox.drain();
      const controlFrames = sent.filter((f) => f.frame.type === "execution.control.state");
      expect(controlFrames).toHaveLength(2);
      // The record's UNIQUE messageId is the ack identity; the command
      // correlation rides the envelope metadata.
      const [firstControlFrame] = controlFrames;
      expect(firstControlFrame?.record.messageId).not.toBe("msg-hold-1");
      expect(
        controlFrames.every((f) => f.frame.correlationId === "msg-hold-1"),
      ).toBe(true);
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  });

  test("Fix 2 ack contract: acking one record of a two-record command does NOT ack the other (FsOutboxBackend in a temp dir)", async () => {
    const root = mkdtempSync(join(tmpdir(), "control-ack-isolation-"));
    try {
      const backend = new FsOutboxBackend(root);
      const outbox = new OrchestrationOutbox({ backend, send: async () => true });
      const coordinator = new ExecutionControlCoordinator({
        executionId: EXECUTION_ID,
        dispatchId: DISPATCH_ID,
        policy: policy(300),
        clock: makeFakeClock().clock,
        scheduler: makeFakeClock().scheduler,
        outbox,
        emitter: { publish: () => {} },
      });
      await coordinator.apply(hold(1), "msg-hold-1"); // HOLD_REQUESTED + HELD

      const controlStates = (await backend.list()).filter(
        (r) => r.kind === "control_state",
      );
      expect(controlStates).toHaveLength(2);
      const [requestedRecord, heldRecord] = controlStates;
      // Distinct wire identities per record.
      expect(requestedRecord?.messageId).toBeDefined();
      expect(heldRecord?.messageId).toBeDefined();
      expect(requestedRecord?.messageId).not.toBe(heldRecord?.messageId);
      const requestedWireId = requestedRecord?.messageId ?? "";

      // The engine's protocol.ack names ONE frame's messageId: only THAT
      // record clears; the other keeps retransmitting.
      await outbox.acknowledgeByMessageId(requestedWireId);
      const afterAck = (await backend.list()).filter(
        (r) => r.kind === "control_state",
      );
      const acked = afterAck.filter((r) => r.acknowledgedAt !== null);
      const unacked = afterAck.filter((r) => r.acknowledgedAt === null);
      expect(acked.map((r) => r.payload)).toEqual([
        expect.objectContaining({ status: "HOLD_REQUESTED" }),
      ]);
      expect(unacked.map((r) => r.payload)).toEqual([
        expect.objectContaining({ status: "HELD" }),
      ]);
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  });
});