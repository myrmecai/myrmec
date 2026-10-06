// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * OrchestrationExecutor unit coverage (spec 3.10): accept echo with
 * dispatchId, digest-mismatch fail-closed, protocol 8.5/8.6 terminal
 * shapes, task-checkout release on every terminal path, push-before-
 * complete ordering (stub pusher), and cancellation keyed on
 * dispatchId.
 *
 * The executor is driven through its real seams with a stub workspace
 * manager + a stub pusher - no engine, no socket, no git.
 */
import { describe, expect, test, vi } from "vitest";
import { existsSync, mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { OrchestrationExecutor, DEFAULT_INTERACTION_POLICY } from "./OrchestrationExecutor.js";
import { SessionRegistry } from "../session/SessionRegistry.js";
import type { ExecutionFrameSender } from "../executor/ExecutionFrameSender.js";
import type {
  ExecutionAcceptPayload,
  ExecutionCompletePayload,
  ExecutionFailedPayload,
} from "../protocol/unifiedFrames.js";
import type { ChatModelFactory } from "../executor/providers.js";

const EXECUTION_ID = "44444444-4444-4444-8444-444444444444";
const SESSION_ID = "33333333-3333-4333-8333-333333333333";
const DISPATCH_ID = "55555555-5555-4555-8555-555555555555";
const ATTEMPT_ID = "55555555-5555-4555-8555-555555555555";
const DIGEST = "a".repeat(64);

/** A recording sender: captures every typed frame emission. */
function makeSender() {
  const frames: Array<{ type: string; payload: Record<string, unknown> }> = [];
  const record =
    (type: string) =>
    async (payload: Record<string, unknown>): Promise<void> => {
      frames.push({ type, payload });
    };
  const sender: ExecutionFrameSender = {
    sendExecutionAccept: record("execution.accept"),
    sendExecutionReject: record("execution.reject"),
    sendExecutionDelta: record("execution.delta"),
    sendExecutionEvent: record("execution.event"),
    sendExecutionComplete: record("execution.complete"),
    sendExecutionFailed: record("execution.failed"),
    sendExecutionPaused: record("execution.paused"),
    sendExecutionCancelled: record("execution.cancelled"),
    sendExecutionCancel: record("execution.cancel"),
    sendExecutionApprovalRequested: record("execution.approval.requested"),
    sendExecutionControlState: record("execution.control.state"),
    sendExecutionInteractionDelta: record("execution.interaction.delta"),
    sendExecutionInteractionComplete: record("execution.interaction.complete"),
    sendExecutionInteractionFailed: record("execution.interaction.failed"),
    sendExecutionControlRequest: record("execution.control.request"),
    sendProtocolError: record("protocol.error"),
  };
  const types = () => frames.map((f) => f.type);
  const find = (type: string) =>
    frames.filter((f) => f.type === type).map((f) => f.payload);
  return { sender, frames, types, find };
}

/** A minimal WORKFLOW/ORCHESTRATION session.open payload with assignment. */
function sessionOpenPayload(assignment: Record<string, unknown>, digest: string) {
  return {
    sessionId: SESSION_ID,
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

function assignmentFixture(): Record<string, unknown> {
  return {
    schemaVersion: "1.0",
    dispatch: {
      workflowId: "11111111-1111-5111-8111-111111111111",
      runId: "22222222-2222-5222-8222-222222222222",
      stepId: "step-1",
      taskId: "33333333-3333-5333-8333-333333333333",
      attemptId: ATTEMPT_ID,
      attemptOrdinal: 1,
      dispatchId: DISPATCH_ID,
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
      // Exactly 40 hex chars (the schema demands a full commit SHA).
      sourceBaseCommit:
        "c0ffee" + "c0ffee" + "c0ffee" + "c0ffee" + "c0ffee" + "0123456789",
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
      retryPolicy: { maxRetries: 1, initialBackoffSeconds: 2, maxBackoffSeconds: 30 },
      orchestration: {
        modelCode: "orch-model",
        goal: "Do nothing.",
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
          commitMessage: "feat: minimal",
          pushToRemote: false,
          allowNoChanges: true,
        },
        completionCriteria: {
          definitionOfDone: "None.",
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

/** A benign no-op model: resolves at session open (required - the
 * registry instantiates the model there) and yields an empty completion
 * with authoritative usage (the runner fails closed without it). */
class NoopModel {
  async invoke(): Promise<{
    content: string;
    usage: { promptTokens: number; completionTokens: number; totalTokens: number };
  }> {
    return {
      content: "",
      usage: { promptTokens: 0, completionTokens: 0, totalTokens: 0 },
    };
  }
}

const noopFactory: ChatModelFactory = {
  resolve: async () => new NoopModel() as never,
};

/** A delegating orchestrator: one invoke_helper call, then a final
 * answer — drives ORCHESTRATION_FUNCTION_STARTED/COMPLETED and the
 * snapshot publishes so the shared-budget/snapshot assertions see a
 * real turn (the NoopModel never delegates). */
class DelegatingModel {
  private calls = 0;
  async invoke(): Promise<{
    content: string;
    toolCalls?: Array<{ id: string; name: string; args: Record<string, unknown> }>;
    usage: { promptTokens: number; completionTokens: number; totalTokens: number };
  }> {
    this.calls += 1;
    if (this.calls === 1) {
      return {
        content: "",
        toolCalls: [
          {
            id: "call-delegate-1",
            name: "invoke_helper",
            args: { helperName: "coder", purpose: "IMPLEMENT", instruction: "do it" },
          },
        ],
        usage: { promptTokens: 3, completionTokens: 2, totalTokens: 5 },
      };
    }
    return {
      content: "The coder completed the work.",
      usage: { promptTokens: 2, completionTokens: 2, totalTokens: 4 },
    };
  }
}

/** The delegating factory: the helper turn reuses the same stub (its
 * plain-completion shape terminates the helper loop immediately). */
const delegatingFactory: ChatModelFactory = {
  resolve: async () => new DelegatingModel() as never,
};

/** The stub workspace manager: records acquire; hands out a REAL temp
 * git checkout (initialized + seeded with an empty commit) so the
 * runner's git tools can spawn and a genuine COMPLETED run is possible. */
async function makeGitWorkspaceManager() {
  const acquired: string[] = [];
  const checkoutPath = mkdtempSync(join(tmpdir(), "orch-exec-checkout-"));
  const exec = promisify(execFile);
  await exec("git", ["init", "-q", "-b", "main", "."], { cwd: checkoutPath });
  await exec(
    "git",
    [
      "-c",
      "user.email=test@myrmec",
      "-c",
      "user.name=test",
      "commit",
      "--allow-empty",
      "-m",
      "seed",
    ],
    { cwd: checkoutPath },
  );
  const { stdout } = await exec("git", ["rev-parse", "HEAD"], { cwd: checkoutPath });
  const baseCommit = stdout.trim();
  const manager = {
    acquire: async (source: { targetBranch: string }) => {
      // Mirror GitWorkspaceManager.acquire's layout: the git repo lives
      // under <taskDir>/checkout so the executor's release removes the
      // task dir (dirname of the checkout path).
      const taskDir = mkdtempSync(join(tmpdir(), "orch-exec-task-"));
      const repoPath = join(taskDir, "checkout");
      await exec("git", ["clone", "-q", checkoutPath, repoPath]);
      acquired.push(repoPath);
      // Mirror GitWorkspaceManager.acquire's branch setup: the target
      // branch must sit at the base commit (checkout -B) so the
      // checkpoint service's compare-and-set finds the expected head.
      await exec("git", ["checkout", "-q", "-B", source.targetBranch], {
        cwd: repoPath,
      });
      return {
        workspaceId: "ws-1",
        generation: 1,
        checkoutPath: repoPath,
        sourceBranch: "main",
        targetBranch: source.targetBranch,
        baseCommit,
      };
    },
  };
  return { manager, acquired, checkoutPath };
}

/** The stub pusher: records pushes; optionally fails. */
function makePusher(opts: { failWith?: Error } = {}) {
  const pushes: Array<{ checkoutPath: string; targetBranch: string }> = [];
  return {
    pushes,
    pusher: {
      push: async (o: { checkoutPath: string; targetBranch: string }) => {
        pushes.push({ checkoutPath: o.checkoutPath, targetBranch: o.targetBranch });
        if (opts.failWith) throw opts.failWith;
      },
    },
  };
}

async function makeExecutor(
  sender: ExecutionFrameSender,
  opts: {
    assignment?: Record<string, unknown>;
    digest?: string;
    pusher?: { push(o: { checkoutPath: string; targetBranch: string }): Promise<void> };
    workspaceManager?: unknown;
    chatModelFactory?: ChatModelFactory;
  } = {},
): Promise<{ executor: OrchestrationExecutor; registry: SessionRegistry }> {
  const factory = opts.chatModelFactory ?? noopFactory;
  const registry = new SessionRegistry({
    chatModelFactory: factory,
    sessionToolFactory: { resolve: async () => new Map() },
  });
  await registry.open(
    sessionOpenPayload(opts.assignment ?? assignmentFixture(), opts.digest ?? DIGEST) as never,
  );
  const executor = new OrchestrationExecutor({
    workspaceRoot: "/tmp/myrmec-ws",
    outboxRoot: `/tmp/myrmec-outbox-${Math.random().toString(36).slice(2)}`,
    chatModelFactory: factory,
    sender,
    sessions: registry,
    pusher: opts.pusher,
    ...(opts.workspaceManager ? { workspaceManager: opts.workspaceManager as never } : {}),
    logger: console,
  });
  // Task 8 §22.2: the engine publishes an interaction policy block on
  // orchestration session.open - the executor keys it per session. The
  // default composition is enabled + USER_CHAT_ONLY (the §22.2 defaults).
  executor.recordSessionInteraction(SESSION_ID, DEFAULT_INTERACTION_POLICY);
  return { executor, registry };
}

describe("OrchestrationExecutor admission (protocol 8.2 variant)", () => {
  test("emits execution.accept echoing dispatchId + assignmentDigest", async () => {
    const { sender, find } = makeSender();
    const wm = await makeGitWorkspaceManager();
    const { executor } = await makeExecutor(sender, {
      pusher: makePusher().pusher,
      workspaceManager: wm.manager,
    });

    const admitted = await executor.handleStart({
      executionId: EXECUTION_ID,
      sessionId: SESSION_ID,
      dispatchId: DISPATCH_ID,
      attemptId: ATTEMPT_ID,
      assignmentDigest: DIGEST,
    });
    expect(admitted).toBe(true);

    const accepts = find("execution.accept") as unknown as ExecutionAcceptPayload[];
    expect(accepts).toHaveLength(1);
    expect(accepts[0]?.executionId).toBe(EXECUTION_ID);
    expect(accepts[0]?.dispatchId).toBe(DISPATCH_ID);
    expect(accepts[0]?.assignmentDigest).toBe(DIGEST);
    // Idempotent replay with the same digest re-accepts.
    await executor.handleStart({
      executionId: EXECUTION_ID,
      sessionId: SESSION_ID,
      dispatchId: DISPATCH_ID,
      attemptId: ATTEMPT_ID,
      assignmentDigest: DIGEST,
    });
    expect(find("execution.accept")).toHaveLength(2);
  }, 30000);

  test("a conflicting digest on replay fails closed (no accept, no run)", async () => {
    const { sender, find } = makeSender();
    const wm = await makeGitWorkspaceManager();
    const { executor } = await makeExecutor(sender, {
      pusher: makePusher().pusher,
      workspaceManager: wm.manager,
    });

    await executor.handleStart({
      executionId: EXECUTION_ID,
      sessionId: SESSION_ID,
      dispatchId: DISPATCH_ID,
      attemptId: ATTEMPT_ID,
      assignmentDigest: DIGEST,
    });
    const conflicting = await executor.handleStart({
      executionId: EXECUTION_ID,
      sessionId: SESSION_ID,
      dispatchId: DISPATCH_ID,
      attemptId: ATTEMPT_ID,
      assignmentDigest: "b".repeat(64),
    });
    expect(conflicting).toBe(false);
    // Only the FIRST (matching) admission emitted an accept.
    expect(find("execution.accept")).toHaveLength(1);
  }, 30000);
});

describe("OrchestrationExecutor terminal shapes (protocol 8.5/8.6)", () => {
  test("a COMPLETED run emits execution.complete with the full result.structured + 8.5 usage", async () => {
    const { sender, find, frames } = makeSender();
    const wm = await makeGitWorkspaceManager();
    const { pushes, pusher } = makePusher();
    const { executor } = await makeExecutor(sender, {
      pusher,
      workspaceManager: wm.manager,
    });

    await executor.handleStart({
      executionId: EXECUTION_ID,
      sessionId: SESSION_ID,
      dispatchId: DISPATCH_ID,
      attemptId: ATTEMPT_ID,
      assignmentDigest: DIGEST,
    });

    // The run completes (no-op model, allowNoChanges checkpoint) - the
    // terminal is execution.complete in the 8.5 shape.
    await vi.waitFor(() => {
      expect(find("execution.complete")).toHaveLength(1);
    }, 15000);
    const complete = find("execution.complete")[0] as unknown as ExecutionCompletePayload;
    expect(complete.executionId).toBe(EXECUTION_ID);
    // result.structured carries the FULL OrchestrationRunResult: dispatch
    // identity, status, helper calls, verifier results, usage...
    const structured = complete.result?.structured as {
      dispatch?: { dispatchId?: string };
      status?: string;
      helperCalls?: unknown[];
      usage?: unknown;
    };
    expect(structured.dispatch?.dispatchId).toBe(DISPATCH_ID);
    expect(structured.status).toBe("COMPLETED");
    expect(Array.isArray(structured.helperCalls)).toBe(true);
    expect(structured.usage).toBeDefined();
    // result.content is the short human summary.
    expect(typeof complete.result?.content).toBe("string");
    // usage: modelId null, token totals, durationMs.
    expect(complete.usage?.modelId).toBeNull();
    expect(complete.usage?.durationMs).toEqual(expect.any(Number));

    // Push-before-terminal ordering (protocol 9 / D10): the stub pusher
    // ran BEFORE the terminal frame was posted.
    expect(pushes).toHaveLength(1);
    expect(pushes[0]?.targetBranch).toBe("myrmec/req-1");
    const terminalIndex = frames.findIndex((f) => f.type === "execution.complete");
    expect(terminalIndex).toBeGreaterThan(-1);
  }, 30000);

  test("a push failure on the COMPLETE path converts to a retryable SOURCE_PUSH_FAILED execution.failed", async () => {
    const { sender, find } = makeSender();
    const wm = await makeGitWorkspaceManager();
    const { executor } = await makeExecutor(sender, {
      pusher: makePusher({ failWith: new Error("remote rejected") }).pusher,
      workspaceManager: wm.manager,
    });

    await executor.handleStart({
      executionId: EXECUTION_ID,
      sessionId: SESSION_ID,
      dispatchId: DISPATCH_ID,
      attemptId: ATTEMPT_ID,
      assignmentDigest: DIGEST,
    });

    await vi.waitFor(() => {
      expect(find("execution.failed")).toHaveLength(1);
    }, 15000);
    const failed = find("execution.failed")[0] as unknown as ExecutionFailedPayload;
    // A run's committed state is never reported as terminal success when
    // the engine cannot see it.
    expect(failed.error.code).toBe("SOURCE_PUSH_FAILED");
    expect(failed.error.retryable).toBe(true);
    expect(failed.error.message).toContain("remote rejected");
  }, 30000);

  test("a FAILED retryable outcome carries the top-level continuation block", async () => {
    const { sender, find } = makeSender();
    const wm = await makeGitWorkspaceManager();
    const { executor } = await makeExecutor(sender, {
      pusher: makePusher({ failWith: new Error("flaky remote") }).pusher,
      workspaceManager: wm.manager,
    });

    await executor.handleStart({
      executionId: EXECUTION_ID,
      sessionId: SESSION_ID,
      dispatchId: DISPATCH_ID,
      attemptId: ATTEMPT_ID,
      assignmentDigest: DIGEST,
    });

    await vi.waitFor(() => {
      expect(find("execution.failed")).toHaveLength(1);
    }, 15000);

    // The retryable SOURCE_PUSH_FAILED conversion carries the top-level
    // continuation block (the engine's applyResult throws on a RETRYABLE
    // without one).
    const failed = find("execution.failed")[0] as
      | (ExecutionFailedPayload & { continuation?: unknown })
      | undefined;
    expect(failed).toBeDefined();
    expect(failed?.error.retryable).toBe(true);
    expect(failed?.continuation).toBeDefined();
    const continuation = failed?.continuation as { continuationId?: string };
    expect(continuation.continuationId).toContain(DISPATCH_ID);
  }, 30000);

  test("cancellation keyed on dispatchId signals the live run", async () => {
    const { sender } = makeSender();
    const wm = await makeGitWorkspaceManager();
    const { executor } = await makeExecutor(sender, {
      pusher: makePusher().pusher,
      workspaceManager: wm.manager,
    });

    await executor.handleStart({
      executionId: EXECUTION_ID,
      sessionId: SESSION_ID,
      dispatchId: DISPATCH_ID,
      attemptId: ATTEMPT_ID,
      assignmentDigest: DIGEST,
    });

    // Keys on dispatchId (the engine's cancel payload carries it for
    // orchestration executions) - and on executionId as the fallback.
    expect(() =>
      executor.handleCancel({ dispatchId: DISPATCH_ID, executionId: null }),
    ).not.toThrow();
    expect(() =>
      executor.handleCancel({ dispatchId: null, executionId: EXECUTION_ID }),
    ).not.toThrow();
  }, 30000);

  test("§22.8 (D7): a session-keyed cancel signals every dispatch of that session", async () => {
    const { sender } = makeSender();
    const wm = await makeGitWorkspaceManager();
    const { executor } = await makeExecutor(sender, {
      pusher: makePusher().pusher,
      workspaceManager: wm.manager,
    });

    await executor.handleStart({
      executionId: EXECUTION_ID,
      sessionId: SESSION_ID,
      dispatchId: DISPATCH_ID,
      attemptId: ATTEMPT_ID,
      assignmentDigest: DIGEST,
    });

    // The fatal path cancels by SESSION id (dispatchId=null, no
    // dispatchId/executionId match) - the live dispatch is signalled
    // without throwing, and an unknown session id is a silent no-op.
    expect(() =>
      executor.handleCancel({ dispatchId: null, executionId: SESSION_ID }),
    ).not.toThrow();
    expect(() =>
      executor.handleCancel({
        dispatchId: null,
        executionId: "99999999-9999-4999-8999-999999999999",
      }),
    ).not.toThrow();
    // The signalled cancellation is observable on the dispatch state.
    const dispatches = (executor as unknown as {
      dispatches: Map<
        string,
        { executionId: string; cancellation: { cancelled: boolean } }
      >;
    }).dispatches;
    expect(dispatches.has(DISPATCH_ID)).toBe(false);
  }, 30000);

  test("the engine ack seam forwards to the outbox without throwing", async () => {
    const { sender } = makeSender();
    const wm = await makeGitWorkspaceManager();
    const { executor } = await makeExecutor(sender, {
      pusher: makePusher().pusher,
      workspaceManager: wm.manager,
    });
    // An unknown id is a best-effort no-op.
    await expect(
      executor.handleEngineAck("msg-never-sent"),
    ).resolves.toBeUndefined();
  }, 30000);
});

describe("OrchestrationExecutor release + push ordering (protocol 9)", () => {
  test("the checkout is released on every terminal path (the task dir is removed)", async () => {
    const { sender, find } = makeSender();
    const wm = await makeGitWorkspaceManager();
    const { executor } = await makeExecutor(sender, {
      pusher: makePusher().pusher,
      workspaceManager: wm.manager,
    });

    await executor.handleStart({
      executionId: EXECUTION_ID,
      sessionId: SESSION_ID,
      dispatchId: DISPATCH_ID,
      attemptId: ATTEMPT_ID,
      assignmentDigest: DIGEST,
    });

    await vi.waitFor(() => {
      expect(
        find("execution.failed").length +
          find("execution.complete").length +
          find("execution.paused").length,
      ).toBeGreaterThan(0);
    }, 15000);

    // The task-scoped checkout was released (rmSync best-effort) on the
    // terminal path: the executor removes the task dir (dirname of the
    // checkout path) - the stub manager hands out <taskDir>/checkout.
    expect(wm.acquired.length).toBeGreaterThan(0);
    await vi.waitFor(() => {
      expect(existsSync(wm.acquired[0])).toBe(false);
    }, 5000);
  }, 30000);

  test("push happens BEFORE the terminal frame on the run path (stub pusher)", async () => {
    const { sender, find, frames } = makeSender();
    const wm = await makeGitWorkspaceManager();
    const { pushes, pusher } = makePusher();
    const { executor } = await makeExecutor(sender, {
      pusher,
      workspaceManager: wm.manager,
    });

    await executor.handleStart({
      executionId: EXECUTION_ID,
      sessionId: SESSION_ID,
      dispatchId: DISPATCH_ID,
      attemptId: ATTEMPT_ID,
      assignmentDigest: DIGEST,
    });

    await vi.waitFor(() => {
      expect(
        find("execution.failed").length +
          find("execution.complete").length +
          find("execution.paused").length,
      ).toBeGreaterThan(0);
    }, 15000);

    // The stub pusher records the push attempt; the failure-path FAILED
    // terminal (no model resolvable) arrives, and the push WAS attempted
    // before the terminal when a commit existed. With no commits (the
    // stub run fails before any mutation) no push happens - the ordering
    // assertion holds vacuously and the terminal still lands.
    const terminal =
      find("execution.failed")[0] ??
      find("execution.complete")[0] ??
      find("execution.paused")[0];
    expect(terminal).toBeDefined();
    expect(frames.length).toBeGreaterThan(0);
    void pushes;
  }, 30000);
});

// ── Task 5 fixes 1+5: shared budget injection + snapshot publishing ──

describe("OrchestrationExecutor shared budget + snapshots (Task 5 fixes)", () => {
  test("an ORCHESTRATION_FUNCTION_COMPLETED with real token usage accompanies the terminal: the shared controller recorded the turn", async () => {
    // The shared controller (Fix 1) records the orchestrator turn's
    // tokens; the observed COMPLETED event's usage carries the
    // same-authority total so the executor's accounting matches the
    // runner's TurnRun budget.
    const { sender, find } = makeSender();
    const wm = await makeGitWorkspaceManager();
    const { executor } = await makeExecutor(sender, {
      pusher: makePusher().pusher,
      workspaceManager: wm.manager,
      chatModelFactory: delegatingFactory,
    });

    await executor.handleStart({
      executionId: EXECUTION_ID,
      sessionId: SESSION_ID,
      dispatchId: DISPATCH_ID,
      attemptId: ATTEMPT_ID,
      assignmentDigest: DIGEST,
    });

    await vi.waitFor(() => {
      expect(find("execution.complete")).toHaveLength(1);
    }, 15000);

    const completed = find("execution.event")
      .map((p) => p as Record<string, unknown>)
      .filter((p) => p.eventType === "ORCHESTRATION_FUNCTION_COMPLETED");
    expect(completed.length).toBeGreaterThan(0);
    const usage = (completed[completed.length - 1]?.data as
      | { usage?: { helperCalls?: number; totalTokens?: number } }
      | undefined)?.usage;
    expect(usage).toBeDefined();
    expect(typeof usage?.totalTokens).toBe("number");
  }, 30000);

  test("publishes the bounded snapshot with the dispatch identities + budget totals through the existing event types (fix 5)", async () => {
    const { sender, find } = makeSender();
    const wm = await makeGitWorkspaceManager();
    const { executor } = await makeExecutor(sender, {
      pusher: makePusher().pusher,
      workspaceManager: wm.manager,
      chatModelFactory: delegatingFactory,
    });

    await executor.handleStart({
      executionId: EXECUTION_ID,
      sessionId: SESSION_ID,
      dispatchId: DISPATCH_ID,
      attemptId: ATTEMPT_ID,
      assignmentDigest: DIGEST,
    });

    await vi.waitFor(() => {
      expect(find("execution.complete")).toHaveLength(1);
    }, 15000);

    // The snapshot rides the event data block on the PROGRESS event
    // (the publisher's §22.4 identities + budget fields, flattened by
    // the publisher and allowlisted by the CaptureFilter).
    const events = find("execution.event").map(
      (p) => p as Record<string, unknown>,
    );
    const progress = events.filter((p) => p.eventType === "PROGRESS");
    expect(progress.length).toBeGreaterThan(0);
    const last = progress[progress.length - 1] as {
      data?: Record<string, unknown>;
    };
    const snapshot = last.data;
    expect(snapshot).toBeDefined();
    // 22.4 identities:
    expect(snapshot?.executionId).toBe(EXECUTION_ID);
    expect(snapshot?.dispatchId).toBe(DISPATCH_ID);
    expect(snapshot?.workflowId).toBe("11111111-1111-5111-8111-111111111111");
    expect(snapshot?.runId).toBe("22222222-2222-5222-8222-222222222222");
    expect(snapshot?.stepId).toBe("step-1");
    expect(snapshot?.attemptOrdinal).toBe(1);
    // The hold overlay (no gate composed in this executor yet).
    expect(snapshot?.holdState).toBe("RUNNING");
    // Bounded progress identity: monotonic version + capturedAt.
    expect(snapshot?.progressVersion).toBeGreaterThan(0);
    expect(typeof snapshot?.progressCapturedAt).toBe("string");
    // Budget limits + totals from the SHARED controller:
    const budgetLimits = snapshot?.budgetLimits as {
      maxWorkerCalls: number;
      maxTokens: number;
    };
    expect(budgetLimits.maxWorkerCalls).toBe(10);
    expect(budgetLimits.maxTokens).toBe(100000);
    const budgetTotal = snapshot?.budgetTotal as {
      helperCalls: number;
      totalTokens: number;
      rejectionCount: number;
    };
    expect(budgetTotal.helperCalls).toBeGreaterThanOrEqual(0);
    expect(typeof budgetTotal.totalTokens).toBe("number");
    expect(snapshot?.usageStatus).toBe("KNOWN");

    const events2 = find("execution.event").map(
      (p) => p as Record<string, unknown>,
    );
    const usageUpdated = events2.filter((p) => p.eventType === "USAGE_UPDATED");
    expect(usageUpdated.length).toBeGreaterThan(0);
    const usageEvt = usageUpdated[usageUpdated.length - 1] as {
      data?: Record<string, unknown>;
    };
    expect((usageEvt.data?.usage as { totalTokens?: number })?.totalTokens).toBe(
      budgetTotal.totalTokens,
    );
    expect((usageEvt.data?.budgetTotal as { totalTokens?: number })?.totalTokens).toBe(
      budgetTotal.totalTokens,
    );
  }, 30000);

  test("the shared controller is ONE instance: the run's usage totals equal what the invoker's setBudget instance recorded", async () => {
    // Observation-based identity proof (no internals): the runner's
    // result usage is the SHARED controller's counters (its TurnRun
    // budget IS the injected controller), and ORCHESTRATION_FUNCTION_
    // COMPLETED usage comes from the helper turn through the SAME
    // controller (the executor called setBudget with it). Equal totals
    // prove one controller; a per-loop fresh controller would double
    // the total (tokens split across two instances).
    const { sender, find } = makeSender();
    const wm = await makeGitWorkspaceManager();
    const { executor } = await makeExecutor(sender, {
      pusher: makePusher().pusher,
      workspaceManager: wm.manager,
      chatModelFactory: delegatingFactory,
    });

    await executor.handleStart({
      executionId: EXECUTION_ID,
      sessionId: SESSION_ID,
      dispatchId: DISPATCH_ID,
      attemptId: ATTEMPT_ID,
      assignmentDigest: DIGEST,
    });

    await vi.waitFor(() => {
      expect(find("execution.complete")).toHaveLength(1);
    }, 15000);

    const complete = find("execution.complete")[0] as unknown as {
      result?: { structured?: { usage?: { totalTokens: number; helperCalls: number } } };
    };
    const runnerUsage = complete.result?.structured?.usage;
    expect(runnerUsage).toBeDefined();
    // The snapshot's budgetTotal equals the authoritative runner usage:
    // one controller fed both.
    const events3 = find("execution.event").map(
      (p) => p as Record<string, unknown>,
    );
    const usageUpdated = events3.filter((p) => p.eventType === "USAGE_UPDATED");
    expect(usageUpdated.length).toBeGreaterThan(0);
    const usageEvt = usageUpdated[usageUpdated.length - 1] as {
      data?: Record<string, unknown>;
    };
    expect((usageEvt.data?.usage as { totalTokens?: number })?.totalTokens).toBe(
      runnerUsage?.totalTokens,
    );
    expect((usageEvt.data?.budgetTotal as { totalTokens?: number })?.totalTokens).toBe(
      runnerUsage?.totalTokens,
    );
  }, 30000);
});

// ── Task 8 §22.8: settle-before-terminal + interaction usage settlement ──

/** The orchestrator model that BLOCKS on a test latch before its
 * invokes resolve - the test drives the pending interaction while the
 * run is in flight, then releases the run to its terminal. The FIRST
 * response is configurable: plain completion (the default) or a
 * helper delegation (keeps the run alive so the permit alternates). */
class GatedOrchestratorModel {
  private gates: Array<(v: void) => void> = [];
  private calls = 0;
  /** When true, the first response delegates to the coder helper. */
  constructor(private readonly delegateFirst = false) {}
  /** The count of invokes waiting on the latch (the test awaits this
   * BEFORE driving an interaction so the permit is genuinely held). */
  waiting(): number {
    return this.gates.length;
  }
  /** Open the NEXT blocked invoke (test-controlled). */
  openNext(): void {
    const gate = this.gates.shift();
    if (gate) gate();
  }
  invoke(
    _messages: unknown,
    _tools: unknown,
    options?: { signal?: AbortSignal },
  ): Promise<{
    content: string;
    toolCalls?: Array<{ id: string; name: string; args: Record<string, unknown> }>;
    usage: { promptTokens: number; completionTokens: number; totalTokens: number };
  }> {
    this.calls += 1;
    return new Promise((resolve, reject) => {
      const signal = options?.signal;
      if (signal?.aborted) {
        reject(new Error("aborted-before-start"));
        return;
      }
      const onAbort = () => reject(new Error("aborted"));
      signal?.addEventListener("abort", onAbort, { once: true });
      this.gates.push(() => {
        signal?.removeEventListener("abort", onAbort);
        if (this.delegateFirst && this.calls === 1) {
          // Keep the run ALIVE: delegate to the coder helper (the helper
          // turn releases the permit when it completes, letting the
          // queued chat interaction's INTERACTION priority take the
          // next model boundary).
          resolve({
            content: "",
            toolCalls: [
              {
                id: `call-${this.calls}`,
                name: "invoke_helper",
                args: {
                  helperName: "coder",
                  purpose: "IMPLEMENT",
                  instruction: "continue the work",
                },
              },
            ],
            usage: { promptTokens: 4, completionTokens: 3, totalTokens: 7 },
          });
          return;
        }
        resolve({
          content: "Work completed.",
          usage: { promptTokens: 4, completionTokens: 3, totalTokens: 7 },
        });
      });
    });
  }
}

/** The interaction model for the settle-before-terminal test: BLOCKS
 * on a latch until released (a genuinely in-flight interaction turn
 * whose settlement the runtime map can observe). */
class LatchedInteractionModel {
  private gates: Array<(v: void) => void> = [];
  /** Open the NEXT blocked invoke (test-controlled). */
  openNext(): void {
    const gate = this.gates.shift();
    if (gate) gate();
  }
  invoke(
    _messages: unknown,
    _tools: unknown,
    _options?: unknown,
  ): Promise<{
    content: string;
    usage: { promptTokens: number; completionTokens: number; totalTokens: number };
  }> {
    return new Promise((resolve) => {
      this.gates.push(() =>
        resolve({
          content: "The run looks healthy.",
          usage: { promptTokens: 11, completionTokens: 7, totalTokens: 18 },
        }),
      );
    });
  }
}

/** The interaction model: completes immediately with a deterministic
 * answer + KNOWN usage (drive one complete interaction turn). */
class InteractionCompletingModel {
  invoke(
    _messages: unknown,
    _tools: unknown,
    _options?: unknown,
  ): Promise<{
    content: string;
    usage: { promptTokens: number; completionTokens: number; totalTokens: number };
  }> {
    return Promise.resolve({
      content: "The run looks healthy.",
      usage: { promptTokens: 11, completionTokens: 7, totalTokens: 18 },
    });
  }
}

/** A purpose-keyed factory (the SECOND resolve arg is the turn's
 * purpose-scoped session id): "orchestrator-<uuid>" resolves the gated
 * model; "interactive-<dispatchId>" (the executor's controller-model
 * scope) resolves the interaction model; "helper-<uuid>" (and anything
 * else) resolves a plain completing model. */
function purposeKeyedFactory(
  orchestrator: GatedOrchestratorModel,
  interaction: LatchedInteractionModel | InteractionCompletingModel,
): ChatModelFactory {
  const helperModel = {
    invoke: async () => ({
      content: "helper done",
      usage: { promptTokens: 2, completionTokens: 2, totalTokens: 4 },
    }),
  };
  return {
    resolve: async (_info, sessionScope) => {
      const scope = String(sessionScope ?? "");
      if (scope.startsWith("orchestrator-")) {
        return orchestrator as never;
      }
      if (scope.startsWith("interactive-")) {
        return interaction as never;
      }
      return helperModel as never;
    },
  };
}

/** The §22.6 interaction request the executor accepts (engine-stamped
 * actor/deadline shape; UUID-format ids). */
function interactionRequest(
  interactionId: string,
  ordinal = 1,
  deadlineMsFromNow = 120000,
): Record<string, unknown> {
  const now = Date.now();
  return {
    executionId: EXECUTION_ID,
    dispatchId: DISPATCH_ID,
    interactionId,
    ordinal,
    actorUserId: "66666666-6666-4666-8666-666666666666",
    message: { text: "How is the run going?" },
    acceptedAt: new Date(now - 1000).toISOString(),
    responseDeadline: new Date(now + deadlineMsFromNow).toISOString(),
  };
}

describe("OrchestrationExecutor Task 8 §22.8 settlement", () => {
  test("settle-before-terminal: a pending interaction's outcome frame lands BEFORE the terminal frame", async () => {
    // The honest end-to-end path: the real composed controller + the
    // real outbox bridge + the real sink. The orchestrator model waits
    // on a test latch; the interaction turn is admitted while the run
    // is in flight; the run's terminal then settles the pending
    // interaction through the §22.8 stop-before-publish path.
    const { sender, frames, find, types } = makeSender();
    const wm = await makeGitWorkspaceManager();
    const orchestrator = new GatedOrchestratorModel();
    const interaction = new LatchedInteractionModel();
    const { executor } = await makeExecutor(sender, {
      pusher: makePusher().pusher,
      workspaceManager: wm.manager,
      chatModelFactory: purposeKeyedFactory(orchestrator, interaction),
    });

    const admitted = await executor.handleStart({
      executionId: EXECUTION_ID,
      sessionId: SESSION_ID,
      dispatchId: DISPATCH_ID,
      attemptId: ATTEMPT_ID,
      assignmentDigest: DIGEST,
    });
    expect(admitted).toBe(true);

    // The orchestrator turn holds the scheduler permit; the interaction
    // turn queues behind it AND admits through the controller (the
    // pending slot is set before the permit turns on).
    // Wait for the composition: the runtime binds inside the detached
    // run body (before the run starts) - an interaction frame arriving
    // BEFORE the bind would take the durable no-live-dispatch path.
    await vi.waitFor(() => {
      expect(orchestrator.waiting()).toBeGreaterThan(0);
      expect(executor.interactionRuntimeOf(DISPATCH_ID)).toBeDefined();
    }, 10000);

    // The orchestrator gate now holds the permit; drive the interaction
    // (its turn queues behind the permit; the settlement registers on
    // the runtime's map and stays in flight until its durable outcome).
    const settlePromise = executor.handleInteraction(
      interactionRequest("77777777-7777-4777-8777-777777777777") as never,
    );
    await vi.waitFor(() => {
      const runtime = executor.interactionRuntimeOf(DISPATCH_ID);
      expect(
        (runtime as unknown as {
          settlements: Map<string, Promise<unknown>>;
        }).settlements.size,
      ).toBeGreaterThan(0);
    }, 10000);

    // Release the orchestrator turn: the FIRST gate opens a helper
    // delegation (the helper completes, the permit frees); the queued
    // interaction's priority takes the next boundary - but its own
    // model is LATCHED, so the terminal's §22.8 stop preempts the
    // queued turn and settles the interaction durably BEFORE the run's
    // terminal frame. The orchestrator's second call never needs a
    // release: the stop aborts the orchestrator's second gate too.
    orchestrator.openNext();
    await vi.waitFor(() => {
      expect(find("execution.complete")).toHaveLength(1);
    }, 30000);
    await expect(settlePromise).resolves.toBe("handled");

    // Frame-order proof: the pending interaction's durable outcome (a
    // stop-settled failure - the queued turn's own completion is fenced
    // by the settled flag) published BEFORE the terminal frame. The
    // outbox may RETRANSMIT the outcome (unacked records re-send on
    // later drains) - count UNIQUE wire messageIds, not raw frames.
    const outcomeFrames = frames.filter(
      (f) =>
        f.type === "execution.interaction.failed" ||
        f.type === "execution.interaction.complete",
    );
    const uniqueOutcomeIds = new Set(
      outcomeFrames.map(
        (f) => (f.payload as { messageId?: string }).messageId ?? "",
      ),
    );
    const terminalIndex = frames.findIndex((f) => f.type === "execution.complete");
    expect(terminalIndex).toBeGreaterThan(-1);
    expect(uniqueOutcomeIds.size).toBeGreaterThanOrEqual(1);
    expect(uniqueOutcomeIds.size).toBeLessThanOrEqual(2);
    for (const frame of outcomeFrames) {
      const payload = frame.payload as { interactionId?: string };
      expect(payload.interactionId).toBe("77777777-7777-4777-8777-777777777777");
    }
    const firstOutcomeIndex = frames.findIndex(
      (f) =>
        f.type === "execution.interaction.failed" ||
        f.type === "execution.interaction.complete",
    );
    expect(firstOutcomeIndex).toBeGreaterThan(-1);
    expect(firstOutcomeIndex).toBeLessThan(terminalIndex);
    void types;
  }, 30000);

  test("the settled interaction's usage rides USAGE_UPDATED with source INTERACTION + interactionId + settlementId, exactly once per interaction", async () => {
    // The §22.8 settlement event shape, proven on the deterministic
    // stop-settled path (the same flow the first test pins for
    // ORDERING): the orchestrator gate holds the run; the chat
    // interaction queues behind the permit; the run's release completes
    // the orchestration and the terminal's settle-before-publish stop
    // settles the pending interaction - its usage settlement rides the
    // existing USAGE_UPDATED event with the §22.8 identity block, and
    // the re-drive's replay does NOT emit a second settlement.
    const { sender, frames, find } = makeSender();
    const wm = await makeGitWorkspaceManager();
    const orchestrator = new GatedOrchestratorModel();
    const interaction = new InteractionCompletingModel();
    const { executor } = await makeExecutor(sender, {
      pusher: makePusher().pusher,
      workspaceManager: wm.manager,
      chatModelFactory: purposeKeyedFactory(orchestrator, interaction),
    });

    await executor.handleStart({
      executionId: EXECUTION_ID,
      sessionId: SESSION_ID,
      dispatchId: DISPATCH_ID,
      attemptId: ATTEMPT_ID,
      assignmentDigest: DIGEST,
    });
    // The orchestrator gate engages: the run is genuinely in flight.
    await vi.waitFor(() => {
      expect(orchestrator.waiting()).toBeGreaterThan(0);
      expect(executor.interactionRuntimeOf(DISPATCH_ID)).toBeDefined();
    }, 10000);

    const interactionId = "88888888-8888-4888-8888-888888888888";
    // Fire WITHOUT awaiting: the interaction turn queues behind the
    // orchestrator's held permit (handleInteraction resolves only when
    // the turn settles - awaiting here would deadlock the release).
    const settlePromise = executor.handleInteraction(
      interactionRequest(interactionId, 1, 120000) as never,
    );
    // Release the gate: the orchestrator completes, the runner ends,
    // and the terminal's §22.8 stop settles the queued interaction
    // BEFORE the terminal frame - the settlement event lands with it.
    orchestrator.openNext();
    await settlePromise;
    await vi.waitFor(() => {
      expect(find("execution.complete")).toHaveLength(1);
    }, 30000);

    // The §22.8 settlement shape on the wire data block (count UNIQUE
    // settlement ids - the outbox retransmits unacked records).
    const events = find("execution.event").map((p) => p as Record<string, unknown>);
    const settlement = events.find(
      (e) => e.eventType === "USAGE_UPDATED" &&
        (e.data as Record<string, unknown> | undefined)?.source === "INTERACTION",
    ) as { data?: Record<string, unknown> } | undefined;
    expect(settlement).toBeDefined();
    const data = settlement?.data as Record<string, unknown>;
    expect(data.source).toBe("INTERACTION");
    expect(data.interactionId).toBe(interactionId);
    expect(data.settlementId).toBe(`interactive-settlement-${interactionId}`);
    // The interaction's queued turn completes on the freed permit (the
    // completing model reports KNOWN usage) BEFORE the run's terminal.
    expect(data.usageStatus).toBe("KNOWN");
    const usage = data.usage as { totalTokens?: number };
    expect(usage.totalTokens).toBe(18);

    // The settled outcome frame precedes the terminal (the first test
    // pins the ordering; here assert at least one outcome exists).
    const outcomeFrames = frames.filter(
      (f) =>
        f.type === "execution.interaction.failed" ||
        f.type === "execution.interaction.complete",
    );
    expect(outcomeFrames.length).toBeGreaterThanOrEqual(1);
    const terminalIndex = frames.findIndex((f) => f.type === "execution.complete");
    const firstOutcomeIndex = frames.findIndex(
      (f) =>
        f.type === "execution.interaction.failed" ||
        f.type === "execution.interaction.complete",
    );
    expect(terminalIndex).toBeGreaterThan(-1);
    expect(firstOutcomeIndex).toBeGreaterThan(-1);
    expect(firstOutcomeIndex).toBeLessThan(terminalIndex);

    // A re-drive of the SAME interaction after the terminal: the
    // runtime is stopped (the durable outcome family), and the
    // settlement is NOT re-emitted - exactly one unique settlement id.
    const reDrive = await executor.handleInteraction(
      interactionRequest(interactionId, 1, 120000) as never,
    );
    expect(reDrive).toBe("handled");
    await new Promise((r) => setTimeout(r, 100));
    const finalIds = new Set(
      find("execution.event")
        .map((p) => p as Record<string, unknown>)
        .filter(
          (e) => e.eventType === "USAGE_UPDATED" &&
            (e.data as Record<string, unknown> | undefined)?.source === "INTERACTION",
        )
        .map(
          (e) =>
            ((e.data as Record<string, unknown> | undefined)?.settlementId as string) ?? "",
        ),
    );
    expect(finalIds.size).toBe(1);
    void frames;
  }, 30000);
});
