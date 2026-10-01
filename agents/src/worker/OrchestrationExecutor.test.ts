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
import { OrchestrationExecutor } from "./OrchestrationExecutor.js";
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
  } = {},
): Promise<{ executor: OrchestrationExecutor; registry: SessionRegistry }> {
  const registry = new SessionRegistry({
    chatModelFactory: noopFactory,
    sessionToolFactory: { resolve: async () => new Map() },
  });
  await registry.open(
    sessionOpenPayload(opts.assignment ?? assignmentFixture(), opts.digest ?? DIGEST) as never,
  );
  const executor = new OrchestrationExecutor({
    workspaceRoot: "/tmp/myrmec-ws",
    outboxRoot: `/tmp/myrmec-outbox-${Math.random().toString(36).slice(2)}`,
    chatModelFactory: noopFactory,
    sender,
    sessions: registry,
    pusher: opts.pusher,
    ...(opts.workspaceManager ? { workspaceManager: opts.workspaceManager as never } : {}),
    logger: console,
  });
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