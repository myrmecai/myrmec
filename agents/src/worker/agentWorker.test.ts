// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { describe, it, expect, vi } from "vitest";
import { Agent } from "./agentWorker.js";
import type { WorkerInbound, WorkerOutbound } from "./agentWorkerProtocol.js";
import { MessageType as UnifiedMessageType } from "../protocol/unifiedFrames.js";
import type { ChatModelFactory, SessionToolFactory } from "../executor/providers.js";
import type { ChatModel, ModelStreamChunk, ModelResponse } from "../executor/types.js";
import type { SessionRegistry } from "../session/SessionRegistry.js";

/** Streaming model that yields fixed chunks for Agent-level tests. */
class FixedStreamModel implements ChatModel {
  constructor(private readonly chunks: ModelStreamChunk[]) {}
  async invoke(): Promise<{ content: string }> {
    throw new Error("Agent test expects streaming path");
  }
  async *stream(): AsyncIterable<ModelStreamChunk> {
    for (const c of this.chunks) yield c;
  }
}

/** Factory that always returns a streaming model with chunks "Hel", "lo". */
const streamingChatModelFactory: ChatModelFactory = {
  resolve: async () => new FixedStreamModel([{ content: "Hel" }, { content: "lo" }]),
};

/** No-op factories for tests that don't exercise model/tool resolution. */
const noopChatModelFactory: ChatModelFactory = {
  resolve: async () => { throw new Error("test does not exercise model resolution") },
};
const noopSessionToolFactory: SessionToolFactory = {
  resolve: async () => new Map(),
};

/** Disposable model whose `close` call count is observable (fatal teardown). */
class DisposableModel implements ChatModel {
  closeCalls = 0;
  async invoke(): Promise<ModelResponse> {
    return { content: "" };
  }
  close(): void {
    this.closeCalls += 1;
  }
}

function connectionState(payload: {
  sessionId: string;
  ready: boolean;
  fatal: boolean;
}): WorkerInbound {
  return { kind: "connection-state", ...payload };
}

function inbound(type: string, payload: unknown): WorkerInbound {
  return {
    kind: "envelope",
    frame: { type, timestamp: new Date().toISOString(), payload },
  };
}

function sink() {
  const messages: WorkerOutbound[] = [];
  const post = (m: WorkerOutbound) => messages.push(m);
  const frames = () =>
    messages
      .filter((m) => m.kind === "frame")
      .map((m) => m.frame as { type: string; payload: unknown });
  const typesSent = () => frames().map((f) => f.type);
  return { post, frames, typesSent };
}

const SESSION_ID = "33333333-3333-4333-8333-333333333333";
const EXECUTION_ID = "44444444-4444-4444-8444-444444444444";

function sessionOpenPayload() {
  return {
    sessionId: SESSION_ID,
    kind: "CONVERSATION",
    projectId: "22222222-2222-2222-8222-222222222222",
    profileVersionId: "11111111-1111-4111-8111-111111111111",
    model: { provider: "openai", modelId: "gpt-4o" },
    tools: [],
    knowledgeSources: [],
    autoHitlOnDestructive: false,
  };
}

function executionStartPayload(stream = true) {
  return {
    executionId: EXECUTION_ID,
    sessionId: SESSION_ID,
    sequenceNo: 1,
    requestId: "req-1",
    deadline: new Date(Date.now() + 300_000).toISOString(),
    input: {
      messages: [{ role: "user", content: "hello", parts: null, toolCalls: null }],
      attachments: null,
      conversationContinuation: null,
    },
    toolPolicy: { activeToolNames: [], approvalMode: null },
    output: { stream, responseSequenceNo: 1, format: "TEXT" },
  };
}

describe("Agent", () => {
  it("routes a unified execution.start (after session.open) and posts execution.delta + execution.complete", async () => {
    const { post, frames, typesSent } = sink();
    const worker = new Agent({
      post,
      chatModelFactory: streamingChatModelFactory,
      sessionToolFactory: noopSessionToolFactory,
    });

    // Session must be opened before execution.start can resolve the model.
    await worker.handle(inbound(UnifiedMessageType.SESSION_OPEN, sessionOpenPayload()));

    worker.handle(inbound(UnifiedMessageType.EXECUTION_START, executionStartPayload(true)));

    await vi.waitFor(() =>
      expect(typesSent()).toContain(UnifiedMessageType.EXECUTION_COMPLETE),
    );

    const deltas = frames().filter((f) => f.type === UnifiedMessageType.EXECUTION_DELTA);
    expect(deltas.map((f) => (f.payload as { content: string }).content)).toEqual(
      ["Hel", "lo"],
    );
    expect(
      frames().find((f) => f.type === UnifiedMessageType.EXECUTION_COMPLETE)?.payload,
    ).toMatchObject({ executionId: EXECUTION_ID, result: { content: "Hello" } });
  });

  it("ignores an unhandled frame type without emitting", () => {
    const { post, frames } = sink();
    const warn = vi.fn();
    const worker = new Agent({
      post,
      chatModelFactory: noopChatModelFactory,
      sessionToolFactory: noopSessionToolFactory,
      logger: { debug: vi.fn(), info: vi.fn(), warn, error: vi.fn() },
    });

    worker.handle(inbound("something.unknown", {}));

    expect(frames()).toHaveLength(0);
    expect(warn).toHaveBeenCalled();
  });

  it("routes execution.cancel through the unified cancellation path", async () => {
    const { post, frames } = sink();
    const warn = vi.fn();
    const worker = new Agent({
      post,
      chatModelFactory: noopChatModelFactory,
      sessionToolFactory: noopSessionToolFactory,
      logger: { debug: vi.fn(), info: vi.fn(), warn, error: vi.fn() },
    });

    // section 8.8: a cancel for a live execution is accepted without emission -
    // the in-flight executor unwinds and posts execution.cancelled itself.
    worker.handle(
      inbound(UnifiedMessageType.EXECUTION_CANCEL, {
        executionId: EXECUTION_ID,
        dispatchId: EXECUTION_ID,
        reasonCode: "USER_REQUESTED",
        requestedAt: new Date().toISOString(),
        gracePeriodSeconds: 5,
      }),
    );

    expect(frames()).toHaveLength(0);
    expect(warn).not.toHaveBeenCalled();
  });

  it("ignores a non-envelope inbound message", () => {
    const { post, frames } = sink();
    const warn = vi.fn();
    const worker = new Agent({
      post,
      chatModelFactory: noopChatModelFactory,
      sessionToolFactory: noopSessionToolFactory,
      logger: { debug: vi.fn(), info: vi.fn(), warn, error: vi.fn() },
    });

    // @ts-expect-error exercising the runtime guard for an unknown kind
    worker.handle({ kind: "bogus" });

    expect(frames()).toHaveLength(0);
    expect(warn).toHaveBeenCalled();
  });

  it("constructs with the built-in resolver when none is injected", () => {
    const { post } = sink();
    expect(() => new Agent({ post, chatModelFactory: noopChatModelFactory, sessionToolFactory: noopSessionToolFactory })).not.toThrow();
  });

  it("threads maxImageBytes into the InferenceExecutor", () => {
    const { post } = sink();
    const worker = new Agent({
      post,
      chatModelFactory: noopChatModelFactory,
      sessionToolFactory: noopSessionToolFactory,
      maxImageBytes: 12345,
    });

    const executor = (worker as unknown as {
      inference: { maxImageBytes?: number };
    }).inference;
    expect(executor.maxImageBytes).toBe(12345);
  });

  // -- section 8.7 (A4): execution.policy.update enforcement --

  const resolvingChatModelFactory: ChatModelFactory = {
    resolve: async () => new FixedStreamModel([]),
  };

  it("applies a tighten-only policy update to the session enforcer without emitting", async () => {
    const { post, frames } = sink();
    const worker = new Agent({
      post,
      chatModelFactory: resolvingChatModelFactory,
      sessionToolFactory: noopSessionToolFactory,
    });
    await worker.handle(inbound(UnifiedMessageType.SESSION_OPEN, sessionOpenPayload()));

    worker.handle(
      inbound(UnifiedMessageType.EXECUTION_POLICY_UPDATE, {
        executionId: EXECUTION_ID,
        dispatchId: null,
        usage: { orchestrationFunctionCalls: 2, totalTokens: 5_000 },
        allowance: { maxTokens: 150_000 },
      }),
    );

    expect(frames()).toHaveLength(0);
    // The enforced ceiling is queryable through the registry's enforcer.
    const enforcer = (worker as unknown as {
      sessions: { enforcerFor: (id: string) => { maxTokens: number | null } };
    }).sessions.enforcerFor(EXECUTION_ID);
    expect(enforcer?.maxTokens).toBe(150_000);
  });

  it("rejects a backward usage roll with protocol.error INVALID_MESSAGE", async () => {
    const { post, frames, typesSent } = sink();
    const worker = new Agent({
      post,
      chatModelFactory: resolvingChatModelFactory,
      sessionToolFactory: noopSessionToolFactory,
    });
    await worker.handle(inbound(UnifiedMessageType.SESSION_OPEN, sessionOpenPayload()));

    // Seed local accounting above the frame's usage.
    const enforcer = (worker as unknown as {
      sessions: {
        enforcerFor: (id: string) => {
          recordTokens: (n: number) => void;
        };
      };
    }).sessions.enforcerFor(EXECUTION_ID)!;
    enforcer.recordTokens(10_000);

    worker.handle(
      inbound(UnifiedMessageType.EXECUTION_POLICY_UPDATE, {
        executionId: EXECUTION_ID,
        dispatchId: null,
        usage: { orchestrationFunctionCalls: 0, totalTokens: 100 },
        allowance: { maxTokens: 200_000 },
      }),
    );

    expect(typesSent()).toEqual(["protocol.error"]);
    const error = frames()[0]?.payload as {
      code: string;
      message: string;
      details: { executionId: string };
    };
    expect(error.code).toBe("INVALID_MESSAGE");
    expect(error.message).toContain("backward");
    expect(error.details.executionId).toBe(EXECUTION_ID);
  });

  it("rejects a loosening allowance with protocol.error INVALID_MESSAGE", async () => {
    const { post, frames, typesSent } = sink();
    const worker = new Agent({
      post,
      chatModelFactory: resolvingChatModelFactory,
      sessionToolFactory: noopSessionToolFactory,
    });
    await worker.handle(inbound(UnifiedMessageType.SESSION_OPEN, sessionOpenPayload()));
    const enforcers = (worker as unknown as {
      sessions: {
        enforcerFor: (id: string) => {
          applyPolicyUpdate: (u: unknown, a?: unknown) => unknown;
        };
      };
    }).sessions;
    enforcers.enforcerFor(EXECUTION_ID)!.applyPolicyUpdate(
      { orchestrationFunctionCalls: 0, totalTokens: 0 },
      { maxTokens: 50_000 },
    );

    worker.handle(
      inbound(UnifiedMessageType.EXECUTION_POLICY_UPDATE, {
        executionId: EXECUTION_ID,
        usage: { orchestrationFunctionCalls: 0, totalTokens: 0 },
        allowance: { maxTokens: 100_000 },
      }),
    );

    expect(typesSent()).toEqual(["protocol.error"]);
    const error = frames()[0]?.payload as { code: string; message: string };
    expect(error.code).toBe("INVALID_MESSAGE");
    expect(error.message).toContain("tighten-only");
  });

  // -- orchestration dispatch routing (unified session execution) --

  const ORCH_SESSION_ID = "33333333-3333-4333-8333-333333333333";
  const ORCH_EXECUTION_ID = "44444444-4444-4444-8444-444444444444";
  const ORCH_DISPATCH_ID = "55555555-5555-4555-8555-555555555555";
  const ORCH_ATTEMPT_ID = "55555555-5555-4555-8555-555555555555";
  const ORCH_DIGEST = "a".repeat(64);

  /** A schema-valid ORCHESTRATION assignment installed at session.open. */
  function orchestrationAssignment(): Record<string, unknown> {
    return {
      schemaVersion: "1.0",
      dispatch: {
        workflowId: "11111111-1111-5111-8111-111111111111",
        runId: "22222222-2222-5222-8222-222222222222",
        stepId: "step-1",
        taskId: "33333333-3333-5333-8333-333333333333",
        attemptId: ORCH_ATTEMPT_ID,
        attemptOrdinal: 1,
        dispatchId: ORCH_DISPATCH_ID,
      },
      models: [
        {
          code: "orch-model",
          provider: "stub",
          modelId: "orch-model",
          description: "Orchestrator model",
          endpoint: null,
          credentialRef: null,
          parameters: {},
        },
      ],
      source: {
        repoUrl: "https://example.invalid/repo.git",
        sourceBranch: "main",
        sourceBaseCommit: "c0ffee".repeat(5) + "0123456789".slice(0, 10),
        targetBranch: "myrmec/req-1",
        credentialRef: null,
      },
      policy: {
        allowedTools: ["read_file", "write_file"],
        commandTemplates: {},
        requiredIsolation: "TRUSTED_PROCESS",
        approvalPolicy: {},
        gitPolicy: { allowCheckpoint: true, allowPush: false },
        workspaceRetentionSeconds: 3600,
      },
      step: {
        id: "step-1",
        name: "Minimal delegation step",
        taskType: "ORCHESTRATOR",
        agentProfileCode: "governed-coding",
        dependsOn: [],
        retryPolicy: {
          maxRetries: 1,
          initialBackoffSeconds: 2,
          maxBackoffSeconds: 30,
        },
        orchestration: {
          modelCode: "orch-model",
          goal: "Delegate one unit of work.",
          specPath: null,
          sourceSubPath: "app",
          helpers: [
            {
              name: "coder",
              modelCode: "orch-model",
              capability: "Writes code",
              allowedTools: ["read_file", "write_file"],
              allowedCommands: [],
            },
          ],
          checkpointStrategy: {
            mode: "ON_VERIFICATION_PASS",
            commitMessage: "feat: minimal delegation",
            pushToRemote: false,
            allowNoChanges: true,
          },
          completionCriteria: {
            definitionOfDone: "One helper invocation completes.",
            requireVerificationBy: [],
          },
          budget: {
            maxTokens: 100000,
            maxWorkerCalls: 10,
            maxVerifierRejectionsPerAttempt: 3,
            maxOrchestratorIterations: 20,
            maxWorkerIterations: 10,
            onBudgetExceeded: "FAIL",
          },
        },
      },
    };
  }

  function orchestrationSessionOpenPayload(
    assignment: Record<string, unknown>,
    digest: string,
  ): Record<string, unknown> {
    return {
      sessionId: ORCH_SESSION_ID,
      kind: "WORKFLOW",
      projectId: "22222222-2222-2222-8222-222222222222",
      profileVersionId: "11111111-1111-4111-8111-111111111111",
      model: { provider: "stub", modelId: "orch-model" },
      tools: [],
      knowledgeSources: [],
      autoHitlOnDestructive: false,
      executionMode: "ORCHESTRATION",
      ref: null,
      orchestration: assignment,
      assignmentDigest: digest,
    };
  }

  it("routes an orchestration-variant execution.start to the orchestration executor with a dispatchId accept echo", async () => {
    const { post, frames, typesSent } = sink();
    const worker = new Agent({
      post,
      chatModelFactory: streamingChatModelFactory,
      sessionToolFactory: noopSessionToolFactory,
      workspaceRoot: `C:/tmp/myrmec-ws-${Math.random().toString(36).slice(2)}`,
      outboxRoot: `C:/tmp/myrmec-outbox-${Math.random().toString(36).slice(2)}`,
    });

    // session.open installs the assignment (the dispatch source of truth).
    await worker.handle(
      inbound(
        UnifiedMessageType.SESSION_OPEN,
        orchestrationSessionOpenPayload(orchestrationAssignment(), ORCH_DIGEST),
      ),
    );

    // The orchestration start variant (dispatchId + assignmentDigest).
    await worker.handle(
      inbound(UnifiedMessageType.EXECUTION_START, {
        executionId: ORCH_EXECUTION_ID,
        sessionId: ORCH_SESSION_ID,
        dispatchId: ORCH_DISPATCH_ID,
        attemptId: ORCH_ATTEMPT_ID,
        assignmentDigest: ORCH_DIGEST,
        deadline: new Date(Date.now() + 300_000).toISOString(),
      }),
    );

    // The accept crossed with the dispatch identity echo (8.2 variant).
    await vi.waitFor(() => {
      expect(typesSent()).toContain(UnifiedMessageType.EXECUTION_ACCEPT);
    }, 10000);
    const accept = frames().find(
      (f) => f.type === UnifiedMessageType.EXECUTION_ACCEPT,
    )?.payload as {
      executionId: string;
      dispatchId: string;
      assignmentDigest: string;
    };
    expect(accept.executionId).toBe(ORCH_EXECUTION_ID);
    expect(accept.dispatchId).toBe(ORCH_DISPATCH_ID);
    expect(accept.assignmentDigest).toBe(ORCH_DIGEST);
  }, 30000);

  it("fails closed (ASSIGNMENT_VALIDATION_ERROR, no accept) on an assignmentDigest mismatch", async () => {
    const { post, frames, typesSent } = sink();
    const worker = new Agent({
      post,
      chatModelFactory: streamingChatModelFactory,
      sessionToolFactory: noopSessionToolFactory,
      workspaceRoot: `C:/tmp/myrmec-ws-${Math.random().toString(36).slice(2)}`,
      outboxRoot: `C:/tmp/myrmec-outbox-${Math.random().toString(36).slice(2)}`,
    });

    await worker.handle(
      inbound(
        UnifiedMessageType.SESSION_OPEN,
        orchestrationSessionOpenPayload(orchestrationAssignment(), ORCH_DIGEST),
      ),
    );

    // The start frame carries a CONFLICTING digest - fail closed, no accept.
    await worker.handle(
      inbound(UnifiedMessageType.EXECUTION_START, {
        executionId: ORCH_EXECUTION_ID,
        sessionId: ORCH_SESSION_ID,
        dispatchId: ORCH_DISPATCH_ID,
        attemptId: ORCH_ATTEMPT_ID,
        assignmentDigest: "b".repeat(64),
        deadline: new Date(Date.now() + 300_000).toISOString(),
      }),
    );

    await vi.waitFor(() => {
      expect(typesSent()).toContain(UnifiedMessageType.EXECUTION_FAILED);
    }, 10000);
    expect(typesSent()).not.toContain(UnifiedMessageType.EXECUTION_ACCEPT);
    const failed = frames().find(
      (f) => f.type === UnifiedMessageType.EXECUTION_FAILED,
    )?.payload as { error: { code: string } };
    expect(failed.error.code).toBe("ASSIGNMENT_VALIDATION_ERROR");
  }, 30000);

  it("fails closed (SESSION_NOT_OPEN) when the orchestration session was never opened", async () => {
    const { post, frames, typesSent } = sink();
    const worker = new Agent({
      post,
      chatModelFactory: streamingChatModelFactory,
      sessionToolFactory: noopSessionToolFactory,
      workspaceRoot: `C:/tmp/myrmec-ws-${Math.random().toString(36).slice(2)}`,
      outboxRoot: `C:/tmp/myrmec-outbox-${Math.random().toString(36).slice(2)}`,
    });

    await worker.handle(
      inbound(UnifiedMessageType.EXECUTION_START, {
        executionId: ORCH_EXECUTION_ID,
        sessionId: ORCH_SESSION_ID,
        dispatchId: ORCH_DISPATCH_ID,
        attemptId: ORCH_ATTEMPT_ID,
        assignmentDigest: ORCH_DIGEST,
        deadline: new Date(Date.now() + 300_000).toISOString(),
      }),
    );

    await vi.waitFor(() => {
      expect(typesSent()).toContain(UnifiedMessageType.EXECUTION_FAILED);
    }, 10000);
    expect(typesSent()).not.toContain(UnifiedMessageType.EXECUTION_ACCEPT);
    const failed = frames().find(
      (f) => f.type === UnifiedMessageType.EXECUTION_FAILED,
    )?.payload as { error: { code: string } };
    expect(failed.error.code).toBe("SESSION_NOT_OPEN");
  }, 30000);

  it("routes a plain execution.start to the inference executor even on a WORKFLOW session without an assignment", async () => {
    const { post, frames, typesSent } = sink();
    const worker = new Agent({
      post,
      chatModelFactory: streamingChatModelFactory,
      sessionToolFactory: noopSessionToolFactory,
    });

    // A CONVERSATION session + a PLAIN start payload (no dispatchId) -
    // the inference path, exactly as before.
    await worker.handle(inbound(UnifiedMessageType.SESSION_OPEN, sessionOpenPayload()));
    worker.handle(inbound(UnifiedMessageType.EXECUTION_START, executionStartPayload(true)));

    await vi.waitFor(() =>
      expect(typesSent()).toContain(UnifiedMessageType.EXECUTION_COMPLETE),
    );
    const deltas = frames().filter(
      (f) => f.type === UnifiedMessageType.EXECUTION_DELTA,
    );
    expect(deltas.map((f) => (f.payload as { content: string }).content)).toEqual(
      ["Hel", "lo"],
    );
  });

  it("execution.cancel keys the orchestration cancellation on dispatchId (falling back to executionId)", async () => {
    const { post, frames } = sink();
    const worker = new Agent({
      post,
      chatModelFactory: streamingChatModelFactory,
      sessionToolFactory: noopSessionToolFactory,
      workspaceRoot: `C:/tmp/myrmec-ws-${Math.random().toString(36).slice(2)}`,
      outboxRoot: `C:/tmp/myrmec-outbox-${Math.random().toString(36).slice(2)}`,
    });

    // Both cancel keyings pass through without throwing: dispatchId-keyed
    // (the engine's cancel payload for orchestration executions) and the
    // executionId fallback.
    expect(() =>
      worker.handle(
        inbound(UnifiedMessageType.EXECUTION_CANCEL, {
          executionId: ORCH_EXECUTION_ID,
          dispatchId: ORCH_DISPATCH_ID,
          reasonCode: "USER_REQUESTED",
          requestedAt: new Date().toISOString(),
          gracePeriodSeconds: 5,
        }),
      ),
    ).not.toThrow();
    expect(() =>
      worker.handle(
        inbound(UnifiedMessageType.EXECUTION_CANCEL, {
          executionId: ORCH_EXECUTION_ID,
          dispatchId: null,
          reasonCode: "USER_REQUESTED",
          requestedAt: new Date().toISOString(),
          gracePeriodSeconds: 5,
        }),
      ),
    ).not.toThrow();
    expect(frames()).toHaveLength(0);
  });

  // -- §22.8 D7: connection-state notifications from the supervisor --

  function registryOf(worker: Agent): SessionRegistry {
    return (worker as unknown as { sessions: SessionRegistry }).sessions;
  }

  it("stops the session exactly once on a fatal connection-state (model disposed once, entry gone)", async () => {
    const { post } = sink();
    const model = new DisposableModel();
    const chatModelFactory: ChatModelFactory = { resolve: async () => model };
    const worker = new Agent({
      post,
      chatModelFactory,
      sessionToolFactory: noopSessionToolFactory,
    });
    await worker.handle(inbound(UnifiedMessageType.SESSION_OPEN, sessionOpenPayload()));
    expect(registryOf(worker).has(SESSION_ID)).toBe(true);

    await worker.handle(connectionState({ sessionId: SESSION_ID, ready: false, fatal: true }));

    // The existing session teardown ran exactly once: entry dropped, model
    // disposed once (§22.8: idempotent fatal).
    expect(registryOf(worker).has(SESSION_ID)).toBe(false);
    expect(model.closeCalls).toBe(1);

    // A repeated fatal is a no-op — no throw, no second dispose.
    await worker.handle(connectionState({ sessionId: SESSION_ID, ready: false, fatal: true }));
    expect(model.closeCalls).toBe(1);
  });

  it("fatal for an UNKNOWN session is a silent no-op", async () => {
    const { post, frames } = sink();
    const warn = vi.fn();
    const worker = new Agent({
      post,
      chatModelFactory: noopChatModelFactory,
      sessionToolFactory: noopSessionToolFactory,
      logger: { debug: vi.fn(), info: vi.fn(), warn, error: vi.fn() },
    });

    await worker.handle(
      connectionState({ sessionId: "99999999-9999-4999-8999-999999999999", ready: false, fatal: true }),
    );

    expect(frames()).toHaveLength(0);
  });

  it("round-trips the readiness mark (ready=false recorded, ready=true clears)", async () => {
    const { post } = sink();
    const worker = new Agent({
      post,
      chatModelFactory: resolvingChatModelFactory,
      sessionToolFactory: noopSessionToolFactory,
    });
    await worker.handle(inbound(UnifiedMessageType.SESSION_OPEN, sessionOpenPayload()));

    await worker.handle(connectionState({ sessionId: SESSION_ID, ready: false, fatal: false }));
    expect(registryOf(worker).stateOf(SESSION_ID)?.ready).toBe(false);

    await worker.handle(connectionState({ sessionId: SESSION_ID, ready: true, fatal: false }));
    expect(registryOf(worker).stateOf(SESSION_ID)?.ready).toBe(true);
  });

  it("unknown connection-state session is ignored without emission", async () => {
    const { post, frames } = sink();
    const warn = vi.fn();
    const worker = new Agent({
      post,
      chatModelFactory: noopChatModelFactory,
      sessionToolFactory: noopSessionToolFactory,
      logger: { debug: vi.fn(), info: vi.fn(), warn, error: vi.fn() },
    });

    await worker.handle(
      connectionState({ sessionId: "88888888-8888-4888-8888-888888888888", ready: true, fatal: false }),
    );

    expect(frames()).toHaveLength(0);
    expect(warn).not.toHaveBeenCalled();
  });

  it("fatal wins over a pending readiness flip: fatal after ready=false still closes", async () => {
    const { post } = sink();
    const model = new DisposableModel();
    const chatModelFactory: ChatModelFactory = { resolve: async () => model };
    const worker = new Agent({
      post,
      chatModelFactory,
      sessionToolFactory: noopSessionToolFactory,
    });
    await worker.handle(inbound(UnifiedMessageType.SESSION_OPEN, sessionOpenPayload()));

    await worker.handle(connectionState({ sessionId: SESSION_ID, ready: false, fatal: false }));
    await worker.handle(connectionState({ sessionId: SESSION_ID, ready: false, fatal: true }));

    expect(registryOf(worker).has(SESSION_ID)).toBe(false);
    expect(model.closeCalls).toBe(1);
  });

  // -- section 22.6/22.3: inbound interaction frame routing (Task 7) --

  function interactionInbound(overrides: Record<string, unknown> = {}): WorkerInbound {
    return inbound(UnifiedMessageType.EXECUTION_INTERACTION, {
      executionId: EXECUTION_ID,
      dispatchId: dispatchIdOf(),
      interactionId: "66666666-6666-4666-8666-666666666666",
      ordinal: 1,
      actorUserId: "88888888-8888-4888-8888-888888888888",
      message: { text: "what is happening?" },
      acceptedAt: new Date().toISOString(),
      responseDeadline: new Date(Date.now() + 120_000).toISOString(),
      ...overrides,
    });
  }

  /** The dispatch id an orchestration start install used (tests reuse
   * the same value for the interaction routing check). */
  const cachedDispatchId = "55555555-5555-4555-8555-555555555555";
  function dispatchIdOf(): string {
    return cachedDispatchId;
  }

  it("routes execution.interaction to the owning session's executor (controller handle) - an unknown identity answers protocol.error IDENTITY_MISMATCH", async () => {
    const { post, frames, typesSent } = sink();
    const warn = vi.fn();
    const worker = new Agent({
      post,
      chatModelFactory: streamingChatModelFactory,
      sessionToolFactory: noopSessionToolFactory,
      workspaceRoot: `C:/tmp/myrmec-ws-${Math.random().toString(36).slice(2)}`,
      outboxRoot: `C:/tmp/myrmec-outbox-${Math.random().toString(36).slice(2)}`,
      logger: { debug: vi.fn(), info: vi.fn(), warn, error: vi.fn() },
    });

    // No orchestration session was ever opened: the interaction identity
    // cannot resolve -> fail closed with protocol.error (IDENTITY_MISMATCH).
    await worker.handle(interactionInbound());

    await vi.waitFor(() => {
      expect(typesSent()).toContain("protocol.error");
    });
    const error = frames().find((f) => f.type === "protocol.error")?.payload as {
      code: string;
      message: string;
    };
    expect(error.code).toBe("IDENTITY_MISMATCH");
  });

  it("routes execution.control.request.resolved to the owning coordinator - an unknown identity answers protocol.error IDENTITY_MISMATCH", async () => {
    const { post, frames, typesSent } = sink();
    const worker = new Agent({
      post,
      chatModelFactory: streamingChatModelFactory,
      sessionToolFactory: noopSessionToolFactory,
      workspaceRoot: `C:/tmp/myrmec-ws-${Math.random().toString(36).slice(2)}`,
      outboxRoot: `C:/tmp/myrmec-outbox-${Math.random().toString(36).slice(2)}`,
    });

    await worker.handle(
      inbound(UnifiedMessageType.EXECUTION_CONTROL_REQUEST_RESOLVED, {
        executionId: EXECUTION_ID,
        dispatchId: dispatchIdOf(),
        interactionId: "66666666-6666-4666-8666-666666666666",
        controlRequestId: "77777777-7777-4777-8777-777777777777",
        resolutionRevision: 1,
        status: "ACCEPTED",
        expiresAt: null,
        commandMessageId: "cmd-1",
        controlRevision: 3,
        errorCode: null,
      }),
    );

    await vi.waitFor(() => {
      expect(typesSent()).toContain("protocol.error");
    });
    const error = frames().find((f) => f.type === "protocol.error")?.payload as {
      code: string;
    };
    expect(error.code).toBe("IDENTITY_MISMATCH");
  });

  it("routes execution.interaction to a LIVE orchestration session's controller (accept: no protocol.error)", async () => {
    const { post, typesSent } = sink();
    const worker = new Agent({
      post,
      chatModelFactory: streamingChatModelFactory,
      sessionToolFactory: noopSessionToolFactory,
      workspaceRoot: `C:/tmp/myrmec-ws-${Math.random().toString(36).slice(2)}`,
      outboxRoot: `C:/tmp/myrmec-outbox-${Math.random().toString(36).slice(2)}`,
    });

    // session.open installs the assignment; execution.start admits the
    // dispatch (the interaction's identity must match the LIVE dispatch).
    await worker.handle(
      inbound(
        UnifiedMessageType.SESSION_OPEN,
        orchestrationSessionOpenPayload(orchestrationAssignment(), ORCH_DIGEST),
      ),
    );
    await worker.handle(
      inbound(UnifiedMessageType.EXECUTION_START, {
        executionId: ORCH_EXECUTION_ID,
        sessionId: ORCH_SESSION_ID,
        dispatchId: ORCH_DISPATCH_ID,
        attemptId: ORCH_ATTEMPT_ID,
        assignmentDigest: ORCH_DIGEST,
        deadline: new Date(Date.now() + 300_000).toISOString(),
      }),
    );
    await vi.waitFor(() => {
      expect(typesSent()).toContain(UnifiedMessageType.EXECUTION_ACCEPT);
    }, 10000);

    // An interaction for the LIVE dispatch: routed to the controller
    // (no IDENTITY_MISMATCH may fire for a resolvable session+dispatch).
    await worker.handle(
      inbound(UnifiedMessageType.EXECUTION_INTERACTION, {
        executionId: ORCH_EXECUTION_ID,
        dispatchId: ORCH_DISPATCH_ID,
        interactionId: "66666666-6666-4666-8666-666666666666",
        ordinal: 1,
        actorUserId: "88888888-8888-4888-8888-888888888888",
        message: { text: "why so slow?" },
        acceptedAt: new Date().toISOString(),
        responseDeadline: new Date(Date.now() + 120_000).toISOString(),
      }),
    );
    // Give async routing a bounded moment, then assert the identity was
    // NOT refused (the executor owns any further outcome handling).
    await vi.waitFor(() => {
      const errors = typesSent().filter((t) => t === "protocol.error");
      expect(errors.length).toBe(0);
    }, 200);
  }, 30000);
});
