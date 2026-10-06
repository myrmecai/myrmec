// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * InteractiveController tests (design 14.2-14.6, protocol 22.4-22.6):
 * the embedded reactive chat loop beside the orchestration loop - same
 * attempt, same budget, same model scheduler, SEPARATE history and tool
 * bindings. No real sleeps anywhere: manual timers + blocked promises,
 * mirroring the coordinator/scheduler test harness.
 */
import { describe, expect, it } from "vitest";
import { InteractiveController } from "./InteractiveController.js";
import type { InteractiveControllerOptions } from "./InteractiveController.js";
import { ExecutionControlCoordinator } from "./ExecutionControlCoordinator.js";
import { AttemptModelScheduler } from "./AttemptModelScheduler.js";
import type {
  ExecutionControlRequestResolvedPayload,
  ExecutionInteractionPayload,
  ExecutionInteractionCompletePayload,
  ExecutionInteractionFailedPayload,
  ExecutionInteractionDeltaPayload,
  ExecutionControlRequestPayload,
  InteractionPolicy,
} from "../protocol/unifiedFrames.js";
import type { SafeExecutionEvent } from "./SafeExecutionEvent.js";
import type {
  ChatModel,
  ConversationMessage,
  ModelResponse,
  ModelStreamChunk,
  ToolSpec,
} from "../executor/types.js";
import type { Logger } from "../models/index.js";
import { freezeExecutionSnapshot, type ExecutionSnapshot } from "./ExecutionSnapshot.js";

// ---- ids / fixtures --------------------------------------------------

const EXECUTION_ID = "44444444-4444-4444-8444-444444444444";
const DISPATCH_ID = "55555555-5555-4555-8555-555555555555";
const INTERACTION_A = "66666666-6666-4666-8666-666666666666";
const INTERACTION_B = "77777777-7777-4777-8777-777777777777";

const silentLogger: Logger = {
  debug: () => {},
  info: () => {},
  warn: (...a) => console.log("WRN", ...a),
  error: (...a) => console.log("ERR", ...a),
};

function policy(overrides: Partial<InteractionPolicy> = {}): InteractionPolicy {
  return {
    version: 1,
    enabled: true,
    idleResumeAfterSeconds: 300,
    responseTimeoutSeconds: 120,
    maxInputBytes: 16384,
    maxOutputBytes: 65536,
    maxModelIterations: 8,
    maxHistoryBytes: 262144,
    transcriptRetentionDays: 30,
    contentMode: "USER_CHAT_ONLY",
    ...overrides,
  };
}

function interaction(
  overrides: Partial<ExecutionInteractionPayload> = {},
): ExecutionInteractionPayload {
  return {
    executionId: EXECUTION_ID,
    dispatchId: DISPATCH_ID,
    interactionId: INTERACTION_A,
    ordinal: 1,
    actorUserId: "88888888-8888-4888-8888-888888888888",
    message: { text: "What is the verification doing?" },
    acceptedAt: "2026-10-03T10:01:00.000Z",
    responseDeadline: new Date(Date.now() + 120_000).toISOString(),
    ...overrides,
  };
}

function resolvedFixture(
  overrides: Partial<ExecutionControlRequestResolvedPayload> = {},
): ExecutionControlRequestResolvedPayload {
  return {
    executionId: EXECUTION_ID,
    dispatchId: DISPATCH_ID,
    interactionId: INTERACTION_A,
    controlRequestId: "77777777-7777-4777-8777-777777777777",
    resolutionRevision: 1,
    status: "ACCEPTED",
    expiresAt: null,
    commandMessageId: "cmd-1",
    controlRevision: 4,
    errorCode: null,
    ...overrides,
  };
}

// ---- scripted ChatModel ----------------------------------------------

interface InvokedTurn {
  messages: ConversationMessage[];
  tools: ToolSpec[];
  signal: AbortSignal | null | undefined;
}

interface Recorder {
  controlStates: Array<Record<string, unknown>>;
  interactionCompletes: ExecutionInteractionCompletePayload[];
  interactionFailures: ExecutionInteractionFailedPayload[];
  controlRequests: ExecutionControlRequestPayload[];
  frames: Array<{ type: string; payload: Record<string, unknown> }>;
}

function makeRecorder(): Recorder {
  return {
    controlStates: [],
    interactionCompletes: [],
    interactionFailures: [],
    controlRequests: [],
    frames: [],
  };
}

/** A ChatModel that BLOCKS until the test releases it. Observes the
 * abort signal like a real provider transport would (an observed abort
 * settles the underlying call); the test may also release it manually. */
class BlockedModel implements ChatModel {
  readonly invokes: InvokedTurn[] = [];
  abortSignalSeen: AbortSignal | null = null;
  private manualRelease!: (value: ModelResponse) => void;
  private readonly manual = new Promise<ModelResponse>((resolve) => {
    this.manualRelease = resolve;
  });

  async invoke(
    messages: ConversationMessage[],
    tools: ToolSpec[],
    options?: { signal?: AbortSignal },
  ): Promise<ModelResponse> {
    this.invokes.push({ messages: structuredClone(messages), tools, signal: options?.signal });
    this.abortSignalSeen = options?.signal ?? null;
    const signal = options?.signal;
    const settled = await Promise.race([
      this.manual.then((response) => ({ manual: true as const, response })),
      new Promise<{ manual: false; response: ModelResponse }>((resolve) => {
        if (signal) {
          signal.addEventListener(
            "abort",
            () =>
              resolve({
                manual: false,
                response: {
                  content: "late-after-abort",
                  usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 },
                },
              }),
            { once: true },
          );
        }
      }),
    ]);
    return settled.response;
  }

  /** The test's release: the first turn completes normally. */
  release(): void {
    this.manualRelease({
      content: "first completes normally",
      usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 },
    });
  }
}

/** A ChatModel whose underlying call NEVER settles on abort (the
 * abort-unsupported provider case): the interaction fails at its
 * deadline while the permit stays held until settleLate(). */
class AbortUnobservingModel implements ChatModel {
  readonly invokes: InvokedTurn[] = [];
  private release!: (value: ModelResponse) => void;

  async invoke(
    messages: ConversationMessage[],
    tools: ToolSpec[],
    options?: { signal?: AbortSignal },
  ): Promise<ModelResponse> {
    this.invokes.push({ messages: structuredClone(messages), tools, signal: options?.signal });
    void options;
    return await new Promise<ModelResponse>((resolve) => {
      this.release = resolve;
    });
  }

  settleLate(): void {
    this.release({
      content: "late-after-abort",
      usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 },
    });
  }
}

/** A ChatModel whose call NEVER settles at all (stop-teardown case). */
class NeverModel implements ChatModel {
  readonly invokes: InvokedTurn[] = [];

  async invoke(
    messages: ConversationMessage[],
    tools: ToolSpec[],
    _options?: { signal?: AbortSignal },
  ): Promise<ModelResponse> {
    this.invokes.push({ messages: structuredClone(messages), tools, signal: _options?.signal });
    return await new Promise<ModelResponse>(() => undefined);
  }
}

/** Scripted model (fast): invoke returns queued responses; the streamed
 * variant streams each response's content as ONE chunk plus tool/usage
 * chunks. */
class ScriptedModel implements ChatModel {
  readonly invokes: InvokedTurn[] = [];
  private readonly invokeScript: ModelResponse[] = [];
  private readonly streamScript: ModelResponse[] = [];

  constructor(
    script: ModelResponse[],
    readonly streamed = false,
  ) {
    if (streamed) {
      this.streamScript.push(...script);
    } else {
      this.invokeScript.push(...script);
    }
  }

  async invoke(
    messages: ConversationMessage[],
    tools: ToolSpec[],
    options?: { signal?: AbortSignal },
  ): Promise<ModelResponse> {
    this.invokes.push({ messages: structuredClone(messages), tools, signal: options?.signal });
    const next = this.invokeScript.shift();
    if (!next) throw new Error("ScriptedModel exhausted (invoke)");
    return next;
  }

  async *stream(
    messages: ConversationMessage[],
    tools: ToolSpec[],
    options?: { signal?: AbortSignal },
  ): AsyncIterable<ModelStreamChunk> {
    const next = this.streamScript.shift();
    if (!next) throw new Error("ScriptedModel exhausted (stream)");
    this.invokes.push({ messages: structuredClone(messages), tools, signal: options?.signal });
    if (next.content) {
      yield { content: next.content };
    }
    if (next.toolCalls && next.toolCalls.length > 0) {
      yield { toolCalls: next.toolCalls };
    }
    if (next.usage) {
      yield { usage: next.usage };
    }
  }

  get callCount(): number {
    return this.invokes.length;
  }
}

// ---- harness -----------------------------------------------------------

async function flush(times = 25): Promise<void> {
  for (let i = 0; i < times; i++) await Promise.resolve();
}

interface ManualTimer {
  fire(): void;
}

function makeManualTimerScheduler(): {
  schedule(delayMs: number, callback: () => void): void;
  timers(): ManualTimer[];
} {
  const pending: Array<{ callback: () => void; fired: boolean }> = [];
  return {
    schedule: (_delayMs, callback) => {
      pending.push({ callback, fired: false });
    },
    timers: () =>
      pending
        .filter((t) => !t.fired)
        .map((t) => ({
          fire: () => {
            if (!t.fired) {
              t.fired = true;
              t.callback();
            }
          },
        })),
  };
}

function snapshotOf(): Readonly<ExecutionSnapshot> {
  return freezeExecutionSnapshot(
    {
      executionId: EXECUTION_ID,
      dispatchId: DISPATCH_ID,
      workflowId: "11111111-1111-4111-8111-111111111111",
      runId: "22222222-2222-4222-8222-222222222222",
      stepId: "step-1",
      taskId: "33333333-3333-4333-8333-333333333333",
      attemptId: "55555555-5555-4555-8555-555555555555",
      attemptOrdinal: 1,
      holdState: "RUNNING",
    },
    {
      budgetLimits: {
        maxWorkerCalls: 10,
        maxTokens: 100000,
        maxVerifierRejectionsPerAttempt: 3,
      },
      budgetTotal: { helperCalls: 1, totalTokens: 500, rejectionCount: 0 },
      usageStatus: "KNOWN",
    },
    1,
    0,
    1,
    "2026-10-03T10:00:30.000Z",
  );
}

function makeController(
  recorder: Recorder,
  overrides: {
    model: ChatModel;
    policyOverrides?: Partial<InteractionPolicy>;
    coordinator?: ExecutionControlCoordinator;
    scheduler?: AttemptModelScheduler;
    timerScheduler?: ReturnType<typeof makeManualTimerScheduler>;
  },
): {
  controller: InteractiveController;
  coordinator: ExecutionControlCoordinator;
  scheduler: AttemptModelScheduler;
  timerScheduler: ReturnType<typeof makeManualTimerScheduler>;
} {
  const policyBlock = policy(overrides.policyOverrides ?? {});
  const timerScheduler = overrides.timerScheduler ?? makeManualTimerScheduler();
  const coordinator =
    overrides.coordinator ??
    new ExecutionControlCoordinator({
      executionId: EXECUTION_ID,
      dispatchId: DISPATCH_ID,
      policy: policyBlock,
      clock: { now: () => Date.now() },
      scheduler: timerScheduler,
      outbox: {
        persistControlState: async (payload) => {
          recorder.controlStates.push({ ...payload });
        },
      },
      emitter: {
        publish: (payload) => {
          recorder.frames.push({ type: "execution.control.state", payload });
        },
      },
      logger: silentLogger,
    });
  const scheduler =
    overrides.scheduler ?? new AttemptModelScheduler({ control: coordinator });
  const controller = new InteractiveController({
    executionId: EXECUTION_ID,
    dispatchId: DISPATCH_ID,
    interactionPolicy: policyBlock,
    control: coordinator,
    modelScheduler: scheduler,
    model: overrides.model,
    getSnapshot: snapshotOf,
    recentEvents: (limit: number): ReadonlyArray<SafeExecutionEvent> =>
      [
        { type: "CHECKPOINT_CREATED", at: "2026-10-03T10:00:01.000Z", data: { commitHash: "abc" } },
        { type: "PROGRESS", at: "2026-10-03T10:00:03.000Z", data: { helperCallsCompleted: 1 } },
      ].slice(-limit),
    budget: undefined,
    outbox: {
      persistInteractionComplete: async (payload) => {
        recorder.interactionCompletes.push(payload);
      },
      persistInteractionFailed: async (payload) => {
        recorder.interactionFailures.push(payload);
      },
      persistControlRequest: async (payload) => {
        recorder.controlRequests.push(payload);
      },
    },
    emitter: {
      sendDelta: async (payload) => {
        recorder.frames.push({ type: "execution.interaction.delta", payload });
      },
      sendComplete: async (payload) => {
        recorder.frames.push({ type: "execution.interaction.complete", payload });
      },
      sendFailed: async (payload) => {
        recorder.frames.push({ type: "execution.interaction.failed", payload });
      },
      sendControlRequest: async (payload) => {
        recorder.frames.push({ type: "execution.control.request", payload });
      },
    },
    logger: silentLogger,
  } satisfies InteractiveControllerOptions);
  return { controller, coordinator, scheduler, timerScheduler };
}

// ---- behavioral tests ---------------------------------------------------

describe("InteractiveController (design 14.2-14.6, protocol 22.4-22.6)", () => {
  it("answers a snapshot question while RUNNING: NO hold, no control state, no proposal; usage KNOWN; the slot settles", async () => {
    const recorder = makeRecorder();
    const { controller, coordinator } = makeController(recorder, {
      model: new ScriptedModel([
        {
          content: "Verification is checking the current candidate.",
          usage: { promptTokens: 10, completionTokens: 5, totalTokens: 15 },
        },
      ]),
    });

    await controller.handle(interaction());
    await flush();

    expect(recorder.frames.filter((f) => f.type === "execution.control.request")).toHaveLength(0);
    expect(recorder.controlStates).toHaveLength(0);
    expect(coordinator.snapshot().pendingControlRequestIds).toHaveLength(0);
    expect(coordinator.snapshot().effectiveState).toBe("RUNNING");

    const complete = recorder.interactionCompletes[0];
    expect(complete).toBeDefined();
    expect(complete?.interactionId).toBe(INTERACTION_A);
    expect(complete?.answer.text).toBe("Verification is checking the current candidate.");
    expect(complete?.usageStatus).toBe("KNOWN");
    expect(complete?.usage).toEqual({ inputTokens: 10, outputTokens: 5, modelId: null });
    expect(recorder.interactionFailures).toHaveLength(0);
  });

  it("uses the SAME model config but a SEPARATE history and tool bindings: the orchestrator transcript never sees chat messages", async () => {
    const recorder = makeRecorder();
    const model = new ScriptedModel(
      [
        { content: "answer one", usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 } },
        { content: "answer two", usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 } },
      ],
      true,
    );
    const { controller } = makeController(recorder, { model });

    await controller.handle(interaction());
    await flush();
    await controller.handle(
      interaction({
        interactionId: INTERACTION_B,
        ordinal: 2,
        message: { text: "and the budget?" },
      }),
    );
    await flush();
    expect(model.callCount).toBe(2);
    const secondTurn = model.invokes[1]?.messages ?? [];
    expect(secondTurn[secondTurn.length - 1]?.content).toContain("and the budget?");
    // The second turn's history carried the earlier completed exchange.
    expect(
      secondTurn.some((m) => m.role === "assistant" && m.content === "answer one"),
    ).toBe(true);
    // The bound tools are the interaction allowlist ONLY.
    const boundToolNames = (model.invokes[1]?.tools ?? []).map((t) => t.name).sort();
    expect(boundToolNames).toEqual([
      "get_budget_usage",
      "get_execution_snapshot",
      "get_recent_events",
      "request_cancel",
      "request_continue",
      "request_hold",
    ]);
  });

  it("runs the turn through the AttemptModelScheduler as INTERACTION (jumps the queued orchestration call)", async () => {
    const recorder = makeRecorder();
    const { controller, scheduler } = makeController(recorder, {
      model: new ScriptedModel([
        { content: "the answer", usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 } },
      ]),
    });

    // Occupy the permit with orchestration work first.
    let releaseOrch!: (value: unknown) => void;
    const orchWork = new Promise((resolve) => {
      releaseOrch = resolve;
    });
    const orchRun = scheduler.invoke("ORCHESTRATION", async () => await orchWork);
    await flush();

    const turn = controller.handle(interaction());
    await flush(5);
    // The interaction is queued BEHIND the orchestration permit holder.
    expect(recorder.interactionCompletes.length + recorder.interactionFailures.length).toBe(0);

    releaseOrch("orch-done");
    await orchRun;
    await turn;
    await flush();

    const complete = recorder.interactionCompletes[0];
    expect(complete?.answer.text).toBe("the answer");
  });

  it("duplicate interactionId with identical bytes replays the stored outcome WITHOUT another model call", async () => {
    const recorder = makeRecorder();
    const model = new ScriptedModel([
      {
        content: "first-and-only answer",
        usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 },
      },
    ]);
    const { controller } = makeController(recorder, { model });

    await controller.handle(interaction());
    await flush();
    expect(model.callCount).toBe(1);

    await controller.handle(interaction());
    await flush();

    expect(model.callCount).toBe(1);
    // The replay re-published the stored outcome (idempotent re-delivery).
    expect(recorder.interactionCompletes).toHaveLength(2);
    expect(recorder.interactionCompletes[1]?.interactionId).toBe(INTERACTION_A);
    expect(recorder.interactionCompletes[1]?.answer.text).toBe("first-and-only answer");
    expect(recorder.interactionFailures).toHaveLength(0);
  });

  it("one pending slot: a second CONCURRENT admit fails closed with RESOURCE_IN_USE; the pending one aborts through the provider signal", async () => {
    const recorder = makeRecorder();
    const blocked = new BlockedModel();
    const { controller } = makeController(recorder, { model: blocked });

    const first = controller.handle(interaction());
    await flush(5);

    const second = controller.handle(
      interaction({
        interactionId: INTERACTION_B,
        ordinal: 2,
        message: { text: "second question" },
      }),
    );
    await flush(5);

    const refused = recorder.interactionFailures.find(
      (f) => f.interactionId === INTERACTION_B,
    );
    expect(refused).toBeDefined();
    expect(refused?.error.message).toContain("RESOURCE_IN_USE");
    expect(recorder.interactionCompletes).toHaveLength(0);
    expect(
      recorder.interactionFailures.filter((f) => f.interactionId === INTERACTION_A),
    ).toHaveLength(0);
    // The second admission did NOT reach the model.
    expect(blocked.invokes).toHaveLength(1);

    // Release the first one; it completes normally.
    blocked.release();
    await first;
    await flush();
    expect(recorder.interactionCompletes).toHaveLength(1);
    expect(recorder.interactionCompletes[0]?.answer.text).toBe("first completes normally");
    void second;
  });

  it("binds ONLY the six allowlisted tools (no workspace write tools)", async () => {
    const recorder = makeRecorder();
    const { controller } = makeController(recorder, {
      model: new ScriptedModel([
        { content: "ok", usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 } },
      ]),
    });
    const names = controller.tools.map((t) => t.name).sort();
    expect(names).toEqual([
      "get_budget_usage",
      "get_execution_snapshot",
      "get_recent_events",
      "request_cancel",
      "request_continue",
      "request_hold",
    ]);
  });

  it("the CANCEL proposal returns CONFIRMATION_REQUIRED as the tool disposition - the turn resolves, never waits for a user", async () => {
    const recorder = makeRecorder();
    const { controller } = makeController(recorder, {
      model: new ScriptedModel([
        {
          content: "I requested the cancel; it awaits your confirmation.",
          usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 },
        },
      ]),
    });

    // The proposal wait resolves on the engine's FIRST resolution frame
    // (any status); the interaction then completes with the pending
    // answer - no human wait inside the turn.
    const proposal = controller.proposeControl("CANCEL", "user asked to stop");
    await flush();
    const confirmationId = recorder.controlRequests[0]?.controlRequestId ?? "";
    controller.settleProposalResolution(
      resolvedFixture({
        controlRequestId: confirmationId,
        status: "CONFIRMATION_REQUIRED",
        expiresAt: "2026-10-03T10:05:00.000Z",
      }),
    );
    const disposition = await proposal;
    expect(disposition.status).toBe("CONFIRMATION_REQUIRED");
    expect(disposition.controlRequestId).toBe(confirmationId);

    // The proposal was persisted durably BEFORE the wire emission (22.3):
    // the outbox record exists and the frame followed it.
    expect(recorder.controlRequests[0]?.action).toBe("CANCEL");
    const requestFrame = recorder.frames.find((f) => f.type === "execution.control.request");
    expect(requestFrame).toBeDefined();
  });

  it("provider timeout fails the interaction with INTERACTION_TIMEOUT, persists before emitting, and aborts the provider call", async () => {
    const recorder = makeRecorder();
    const blocked = new BlockedModel();
    const { controller, timerScheduler } = makeController(recorder, {
      model: blocked,
      policyOverrides: { responseTimeoutSeconds: 30 },
    });

    const pending = controller.handle(
      interaction({
        responseDeadline: new Date(Date.parse(interaction().acceptedAt) + 30_000).toISOString(),
      }),
    );
    await flush(5);
    expect(recorder.interactionCompletes).toHaveLength(0);
    expect(recorder.interactionFailures).toHaveLength(0);

    // The deadline fires while the model is still blocked.
    for (const timer of timerScheduler.timers()) timer.fire();
    await pending;
    await flush();

    const failure = recorder.interactionFailures[0];
    expect(failure?.interactionId).toBe(INTERACTION_A);
    expect(failure?.error.errorCode).toBe("INTERACTION_TIMEOUT");
    // Persist BEFORE emit: the failure record landed first.
    const failureIdx = recorder.frames.findIndex(
      (f) => f.type === "execution.interaction.failed",
    );
    expect(failureIdx).toBeGreaterThanOrEqual(0);
    // The abort reached the provider signal; the late result is fenced
    // (no complete outcome after the failure).
    expect(blocked.abortSignalSeen?.aborted).toBe(true);
    expect(recorder.interactionCompletes).toHaveLength(0);
  });

  it("abort-unsupported provider: the interaction fails at its deadline but the scheduler permit is NOT released early; late results are fenced from output and tools", async () => {
    const recorder = makeRecorder();
    const late = new AbortUnobservingModel();
    const { controller, scheduler, timerScheduler } = makeController(recorder, {
      model: late,
      policyOverrides: { responseTimeoutSeconds: 5 },
    });

    const pending = controller.handle(
      interaction({
        responseDeadline: new Date(Date.parse(interaction().acceptedAt) + 5_000).toISOString(),
      }),
    );
    await flush(5);
    expect(late.invokes).toHaveLength(1);

    // Queue an orchestration probe AFTER the interaction holds the
    // permit: it must stay parked until the late call settles.
    let orchStarted = false;
    const orchProbe = scheduler.invoke("ORCHESTRATION", async () => {
      orchStarted = true;
      return "probe";
    });
    await flush(5);
    expect(orchStarted).toBe(false);

    // The deadline fires: the visible interaction fails while the
    // underlying provider call is STILL in flight.
    for (const timer of timerScheduler.timers()) timer.fire();
    await pending;
    await flush();
    expect(recorder.interactionFailures[0]?.error.errorCode).toBe("INTERACTION_TIMEOUT");
    expect(orchStarted).toBe(false); // the permit was NOT released early

    // The provider settles late; its result is fenced (no outcome, no
    // deltas); the permit frees; the queued probe runs.
    late.settleLate();
    await orchProbe;
    await flush();
    expect(orchStarted).toBe(true);
    expect(recorder.interactionCompletes).toHaveLength(0);
    expect(
      recorder.frames.filter((f) => f.type === "execution.interaction.delta"),
    ).toHaveLength(0);
  });

  it("streams fragments through capture/redaction into dense delta frames; the final response is assembled ONCE", async () => {
    const recorder = makeRecorder();
    const model = new ScriptedModel(
      [
        {
          content: "Checking the budget now.",
          toolCalls: [{ id: "c1", name: "get_execution_snapshot", args: {} }],
          usage: { promptTokens: 5, completionTokens: 2, totalTokens: 7 },
        },
        {
          content: "Two helper calls are done; 500 tokens used.",
          usage: { promptTokens: 3, completionTokens: 4, totalTokens: 7 },
        },
      ],
      true,
    );
    const { controller } = makeController(recorder, { model });

    await controller.handle(interaction());
    await flush(120);

    const deltas = recorder.frames
      .filter((f) => f.type === "execution.interaction.delta")
      .map((f) => f.payload as ExecutionInteractionDeltaPayload);
    expect(deltas.length).toBe(2); // one content chunk per streamed call
    deltas.forEach((d, i) => expect(d.index).toBe(i));
    expect(deltas[0]?.text).toBe("Checking the budget now.");
    // No raw provider reasoning/tool fragments ever streamed.
    for (const d of deltas) {
      expect(d.text).not.toContain("tool_call");
      expect(d.text).not.toContain("get_execution_snapshot");
      expect(d.text).not.toContain("{");
    }
    // The FINAL assembled response is authoritative (usage aggregated
    // across both streamed calls, same validation path as invoke).
    const complete = recorder.interactionCompletes[0];
    expect(complete?.answer.text).toBe("Two helper calls are done; 500 tokens used.");
    expect(complete?.usage).toEqual({ inputTokens: 8, outputTokens: 6, modelId: null });
    expect(complete?.usageStatus).toBe("KNOWN");
    expect(complete?.controlRequestIds).toEqual([]);
  });

  it("an invoke-only model falls back to ONE final delta on completion (the safe fallback)", async () => {
    const recorder = makeRecorder();
    class InvokeOnlyModel implements ChatModel {
      async invoke(): Promise<ModelResponse> {
        return {
          content: "single-shot answer",
          usage: { promptTokens: 2, completionTokens: 3, totalTokens: 5 },
        };
      }
    }
    const { controller } = makeController(recorder, { model: new InvokeOnlyModel() });

    await controller.handle(interaction());
    await flush();

    const deltas = recorder.frames
      .filter((f) => f.type === "execution.interaction.delta")
      .map((f) => f.payload as ExecutionInteractionDeltaPayload);
    expect(deltas).toEqual([
      {
        executionId: EXECUTION_ID,
        dispatchId: DISPATCH_ID,
        interactionId: INTERACTION_A,
        index: 0,
        text: "single-shot answer",
      },
    ]);
    expect(recorder.interactionCompletes[0]?.answer.text).toBe("single-shot answer");
  });

  it("capture NONE disables chat content entirely (CAPTURE_BLOCKED); direct controls stay permitted", async () => {
    const recorder = makeRecorder();
    const model = new ScriptedModel([
      {
        content: "should never leak",
        usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 },
      },
    ]);
    const { controller } = makeController(recorder, {
      model,
      policyOverrides: { contentMode: "NONE" },
    });

    await controller.handle(interaction());
    await flush();

    expect(recorder.interactionFailures[0]?.error.errorCode).toBe("CAPTURE_BLOCKED");
    expect(recorder.interactionCompletes).toHaveLength(0);
    expect(
      recorder.frames.filter((f) => f.type === "execution.interaction.delta"),
    ).toHaveLength(0);
    expect(model.callCount).toBe(0); // never invoked under NONE

    // Direct controls stay permitted.
    const proposal = controller.proposeControl("CANCEL");
    await flush();
    const confirmationId = recorder.controlRequests[0]?.controlRequestId ?? "";
    controller.settleProposalResolution(
      resolvedFixture({ controlRequestId: confirmationId, status: "CONFIRMATION_REQUIRED" }),
    );
    expect((await proposal).status).toBe("CONFIRMATION_REQUIRED");
    expect(recorder.controlRequests).toHaveLength(1);
  });

  it("history is bounded to maxHistoryBytes: the OLDEST pair drops first; the current question never truncates", async () => {
    const recorder = makeRecorder();
    const model = new ScriptedModel(
      [
        { content: "A", usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 } },
        { content: "B", usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 } },
        { content: "C", usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 } },
      ],
      true,
    );
    const { controller } = makeController(recorder, {
      model,
      policyOverrides: { maxHistoryBytes: 60 },
    });

    await controller.handle(
      interaction({ message: { text: "question one (long-ish text to consume bytes)" } }),
    );
    await flush();
    await controller.handle(
      interaction({ interactionId: INTERACTION_B, ordinal: 2, message: { text: "question two" } }),
    );
    await flush();
    await controller.handle(
      interaction({
        interactionId: "99999999-9999-4999-8999-999999999999",
        ordinal: 3,
        message: { text: "question three" },
      }),
    );
    await flush();

    // The third turn's transcript: the oldest exchange (question one)
    // was dropped; question two remains; the current question is intact.
    const thirdTurn = model.invokes[2]?.messages ?? [];
    expect(
      thirdTurn.some((m) => String(m.content).includes("question one")),
    ).toBe(false);
    expect(
      thirdTurn.some((m) => String(m.content).includes("question two")),
    ).toBe(true);
    expect(thirdTurn[thirdTurn.length - 1]?.content).toContain("question three");
  });

  it("an answer over maxOutputBytes fails with OUTPUT_LIMIT_EXCEEDED (nothing leaks on the delta channel)", async () => {
    const recorder = makeRecorder();
    const { controller } = makeController(recorder, {
      model: new ScriptedModel(
        [
          {
            content: "this answer is far longer than thirty-two bytes allow",
            usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 },
          },
        ],
        true,
      ),
      policyOverrides: { maxOutputBytes: 32 },
    });

    await controller.handle(interaction());
    await flush(60);

    expect(recorder.interactionFailures[0]?.error.errorCode).toBe("OUTPUT_LIMIT_EXCEEDED");
    expect(recorder.interactionCompletes).toHaveLength(0);
    expect(
      recorder.frames.filter((f) => f.type === "execution.interaction.complete"),
    ).toHaveLength(0);
  });

  it("a streaming fragment failing content inspection is WITHHELD (buffered off the wire; the complete record is authoritative)", async () => {
    const recorder = makeRecorder();
    class LeakyModel implements ChatModel {
      async invoke(): Promise<ModelResponse> {
        throw new Error("streaming path expected");
      }
      async *stream(): AsyncIterable<ModelStreamChunk> {
        yield { content: "The key is " };
        yield { content: "sk-abc123def456-secret" }; // fails inspection
        yield { content: " - rotated already." };
        yield {
          content: "The key was rotated already.",
          usage: { promptTokens: 1, completionTokens: 2, totalTokens: 3 },
        };
      }
    }
    const { controller } = makeController(recorder, { model: new LeakyModel() });

    await controller.handle(interaction());
    await flush(120);

    const deltas = recorder.frames
      .filter((f) => f.type === "execution.interaction.delta")
      .map((f) => f.payload as ExecutionInteractionDeltaPayload);
    // The secret fragment never crossed; safe fragments did.
    expect(deltas.map((d) => d.text)).toEqual([
      "The key is ",
      " - rotated already.",
      "The key was rotated already.",
    ]);
    for (const d of deltas) {
      expect(d.text).not.toContain("sk-abc123def456-secret");
    }
    // The authoritative complete record also carries no secret.
    expect(recorder.interactionCompletes[0]?.answer.text).not.toContain(
      "sk-abc123def456-secret",
    );
  });

  it("stop() fails the pending interaction with EXECUTION_TERMINAL, rejects pending proposal waits, and clears the slot", async () => {
    const recorder = makeRecorder();
    const never = new NeverModel();
    const { controller, coordinator } = makeController(recorder, { model: never });

    const turn = controller.handle(interaction());
    await flush(5);
    const proposal = controller.proposeControl("HOLD");
    await flush();

    controller.stop("TERMINAL");
    await turn;
    await flush();

    expect(recorder.interactionFailures[0]?.error.errorCode).toBe("EXECUTION_TERMINAL");
    await expect(proposal).rejects.toThrow();
    expect(coordinator.snapshot().pendingInteractionId).toBeNull();
  });

  it("stop(CANCEL) fails the pending interaction with EXECUTION_CANCELLED", async () => {
    const recorder = makeRecorder();
    const never = new NeverModel();
    const { controller } = makeController(recorder, { model: never });

    const turn = controller.handle(interaction());
    await flush(5);

    controller.stop("CANCEL");
    await turn;
    await flush();

    expect(recorder.interactionFailures[0]?.error.errorCode).toBe("EXECUTION_CANCELLED");
  });

  it("a snapshot question while HELD runs WITHOUT re-holding (chat is not gated by the hold)", async () => {
    const recorder = makeRecorder();
    const model = new ScriptedModel([
      {
        content: "held but chatting",
        usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 },
      },
    ]);
    const { controller, coordinator } = makeController(recorder, { model });

    // The engine command: HELD (zero leaves -> HELD in the same turn).
    await coordinator.apply(
      {
        executionId: EXECUTION_ID,
        dispatchId: DISPATCH_ID,
        controlRevision: 1,
        action: "HOLD",
        reasonCode: "USER_REQUESTED",
        holdPolicy: { idleResumeAfterSeconds: 300 },
        controlRequestId: null,
      },
      "cmd-hold-1",
    );
    await flush();
    expect(coordinator.snapshot().effectiveState).toBe("HELD");
    const heldBefore = recorder.controlStates.length;

    await controller.handle(interaction());
    await flush();

    expect(model.callCount).toBe(1);
    expect(recorder.interactionCompletes[0]?.answer.text).toBe("held but chatting");
    // STILL HELD: chat never auto-holds nor auto-continues. The ONLY
    // NEW control-state changes (after the hold's own transition pair)
    // are the coordinator's own idle-clock observations
    // (INTERACTION_ACTIVITY/INTERACTION_SETTLED at the unchanged
    // revision - 22.4), never a second hold.
    expect(coordinator.snapshot().effectiveState).toBe("HELD");
    for (const state of recorder.controlStates.slice(heldBefore)) {
      const payload = state as unknown as {
        status: string;
        reasonCode: string;
        controlRevision: number;
      };
      expect(payload.status).toBe("HELD");
      expect(payload.controlRevision).toBe(1);
      expect([
        "INTERACTION_ACTIVITY",
        "INTERACTION_SETTLED",
        "USER_REQUESTED",
      ]).toContain(payload.reasonCode);
    }
  });

  it("conflicting bytes on a duplicate interactionId fail closed (INVALID_MESSAGE, no second model call)", async () => {
    const recorder = makeRecorder();
    const model = new ScriptedModel([
      {
        content: "only once",
        usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 },
      },
    ]);
    const { controller } = makeController(recorder, { model });

    await controller.handle(interaction());
    await flush();

    await controller.handle(
      interaction({ message: { text: "CONFLICTING different bytes" } }),
    );
    await flush();

    const conflict =
      recorder.interactionFailures[recorder.interactionFailures.length - 1];
    expect(conflict?.interactionId).toBe(INTERACTION_A);
    expect(conflict?.error.message).toContain("INVALID_MESSAGE");
    expect(model.callCount).toBe(1);
  });

  it("a crashing model turn fails with MODEL_ERROR and UNKNOWN usage (never estimated, never zero)", async () => {
    const recorder = makeRecorder();
    class ThrowingModel implements ChatModel {
      async invoke(): Promise<ModelResponse> {
        throw new Error("provider exploded mid-chat");
      }
    }
    const { controller } = makeController(recorder, { model: new ThrowingModel() });

    await controller.handle(interaction());
    await flush();

    const failure = recorder.interactionFailures[0];
    expect(failure?.error.errorCode).toBe("MODEL_ERROR");
    expect(failure?.usageStatus).toBe("UNKNOWN");
    expect(failure?.usage).toBeNull();
  });
});
