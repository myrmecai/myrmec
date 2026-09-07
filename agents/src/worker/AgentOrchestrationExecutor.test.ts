// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Engine-mode reproduction of the dispatch execution: runs the
 * AgentOrchestrationExecutor end to end against a real local bare origin
 * with the SAME stub factory the engine adapter uses — no engine, no
 * socket; the sink collects frames. Reproduces slice-F smoke stalls.
 */
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { execFile } from "node:child_process";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { AgentOrchestrationExecutor } from "../worker/AgentOrchestrationExecutor.js";
import { StubChatModelFactory } from "../executor/stub.js";
import type { Envelope } from "../protocol/envelope.js";

const exec = promisify(execFile);

const here = path.dirname(fileURLToPath(import.meta.url));
// agents/src/worker → up 4 = the workspace root holding myrmec/ + myrmec-ee/.
const repoRoot = path.resolve(here, "..", "..", "..", "..");
const stubModule = path.resolve(
  repoRoot, "myrmec-ee", "e2e", "fixtures", "stubs", "orchestration-minimal.ts",
);
let workspaceRoot: string;
let outboxRoot: string;
let originDir: string;
let originUrl: string;
let headCommit: string;

async function createOrigin(): Promise<void> {
  originDir = mkdtempSync(path.join(tmpdir(), "exec-origin-"));
  originUrl = originDir.replace(/\\/g, "/");
  await exec("git", ["init", "--bare", "-b", "main", originDir]);
  const seed = mkdtempSync(path.join(tmpdir(), "exec-seed-"));
  await exec("git", ["init", "-b", "main", seed]);
  await exec(
    "git",
    ["-c", "user.email=e2e@myrmec", "-c", "user.name=e2e", "commit", "--allow-empty", "-m", "seed"],
    { cwd: seed },
  );
  await exec("git", ["push", originUrl, "main"], { cwd: seed });
  const { stdout } = await exec("git", ["rev-parse", "main"], { cwd: originDir });
  headCommit = stdout.trim();
  rmSync(seed, { recursive: true, force: true });
}

describe("AgentOrchestrationExecutor (engine-mode dispatch)", () => {
  beforeEach(async () => {
    workspaceRoot = mkdtempSync(path.join(tmpdir(), "exec-ws-"));
    outboxRoot = mkdtempSync(path.join(tmpdir(), "exec-outbox-"));
    await createOrigin();
  });

  afterEach(() => {
    rmSync(workspaceRoot, { recursive: true, force: true });
    rmSync(outboxRoot, { recursive: true, force: true });
    rmSync(originDir, { recursive: true, force: true });
  });

  test("a valid dispatch runs the step and emits the terminal result", async () => {
    const frames: Envelope[] = [];
    const executor = new AgentOrchestrationExecutor({
      workspaceRoot,
      outboxRoot,
      chatModelFactory: new StubChatModelFactory(stubModule),
      send: (frame) => frames.push(frame),
    });

    const assignment = {
      schemaVersion: "1.0" as const,
      dispatch: {
        workflowId: "11111111-1111-5111-8111-111111111111",
        runId: "22222222-2222-5222-8222-222222222222",
        stepId: "step-1",
        taskId: "33333333-3333-5333-8333-333333333333",
        attemptId: "44444444-4444-5444-8444-444444444444",
        attemptOrdinal: 1,
        dispatchId: "44444444-4444-5444-8444-444444444444",
      },
      models: [
        {
          code: "orch-model",
          provider: "stub",
          modelId: "orch-model",
          description: "Orchestrator model",
          apiEndpoint: null,
          credentialRef: null,
          parameters: {},
        },
        {
          code: "worker-model",
          provider: "stub",
          modelId: "worker-model",
          description: "Implementation model",
          apiEndpoint: null,
          credentialRef: null,
          parameters: {},
        },
      ],
      source: {
        repoUrl: originUrl,
        sourceBranch: "main",
        sourceBaseCommit: headCommit,
        targetBranch: "feat/minimal",
        credentialRef: null,
      },
      policy: {
        allowedTools: ["read_file", "write_file", "list_directory", "create_directory", "execute_command"],
        commandTemplates: {},
        requiredIsolation: "TRUSTED_PROCESS" as const,
        approvalPolicy: { "action:CHECKPOINT": "ALLOW" },
        gitPolicy: { allowCheckpoint: true, allowPush: false },
        workspaceRetentionSeconds: 3600,
      },
      step: {
        id: "step-1",
        name: "Minimal delegation step",
        taskType: "ORCHESTRATOR" as const,
        agentProfileCode: "governed-coding",
        dependsOn: [],
        retryPolicy: { maxRetries: 1, initialBackoffSeconds: 2, maxBackoffSeconds: 30 },
        orchestration: {
          modelCode: "orch-model",
          goal: "Delegate one unit of work to the coder and summarize.",
          specPath: null,
          sourceSubPath: "app",
          workers: [
            {
              name: "coder",
              modelCode: "worker-model",
              capability: "Writes code",
              allowedTools: ["read_file", "write_file"],
              allowedCommands: [],
            },
          ],
          checkpointStrategy: {
            mode: "ON_VERIFICATION_PASS" as const,
            commitMessage: "feat: minimal delegation",
            pushToRemote: false,
            allowNoChanges: true,
          },
          completionCriteria: {
            definitionOfDone: "One worker invocation completes.",
            requireVerificationBy: [],
          },
          budget: {
            maxTokens: 100000,
            maxWorkerCalls: 10,
            maxVerifierRejectionsPerAttempt: 3,
            maxOrchestratorIterations: 20,
            maxWorkerIterations: 10,
            onBudgetExceeded: "FAIL" as const,
          },
        },
      },
    };

    const admitted = await executor.handleAssign({
      requestId: assignment.dispatch.attemptId,
      sessionId: null,
      orchestration: assignment,
      assignmentDigest: "a".repeat(64),
    });
    expect(admitted).toBe(true);

    // The accept frame crossed first (§16.3 order).
    await vi.waitFor(() => {
      expect(frames.map((f) => f.type)).toContain("inference.accept");
    }, 5000);

    // The terminal result arrives through the durable outbox.
    await vi.waitFor(() => {
      expect(frames.map((f) => f.type)).toContain("orchestration.result");
    }, 30000);

    const result = frames.find((f) => f.type === "orchestration.result");
    const payload = result!.payload as { status: string };
    expect(payload.status).toBe("COMPLETED");

    // §17.1: the managed run layout — the checkout lives at
    // <root>/runs/<runId>/<generation>/checkout so the lease manifest,
    // reconciliation, and the engine adapter's artifact walk all address
    // it through the durable run identity.
    const { existsSync } = await import("node:fs");
    const runCheckout = path.join(
      workspaceRoot,
      "runs",
      assignment.dispatch.runId,
      "1",
      "checkout",
    );
    expect(existsSync(runCheckout)).toBe(true);
  }, 60000);
});