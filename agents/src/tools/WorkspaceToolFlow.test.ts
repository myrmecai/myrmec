// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * End-to-end workspace tool flow test: a scripted worker model requests
 * write_file through the real WorkspaceToolFactory and the file must
 * exist in the step workspace with the expected content.
 */
import { describe, it, expect, afterEach } from "vitest";
import { execFile } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync, mkdirSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import { TurnExecutor } from "../executor/TurnExecutor.js";
import type { ChatModel, ConversationMessage, ModelResponse, ToolSpec } from "../executor/types.js";
import { OrchestrationRunner } from "../orchestration/OrchestrationRunner.js";
import { WorkerInvoker } from "../orchestration/WorkerInvoker.js";
import { GitWorkspaceManager, GitWorkspaceScope } from "../workspace/GitWorkspaceManager.js";
import { WorkspaceToolFactory } from "./WorkspaceToolFactory.js";
import { readFileTool } from "./fileTools.js";

const exec = promisify(execFile);

let dirs: string[] = [];
function tmp(name: string): string {
  const d = mkdtempSync(path.join(tmpdir(), `wsflow-${name}-`));
  dirs.push(d);
  return d;
}
afterEach(() => {
  for (const d of dirs) rmSync(d, { recursive: true, force: true });
  dirs = [];
});

/** A fake chat model factory that returns scripted models by modelId. */
function scriptedFactory(models: Record<string, ChatModel>): {
  resolve: (info: { modelId: string }, sessionId: string) => Promise<ChatModel>;
} {
  return {
    resolve: async (info) => {
      const m = models[info.modelId];
      if (!m) throw new Error(`no scripted model for ${info.modelId}`);
      return m;
    },
  };
}

class ScriptedModel implements ChatModel {
  constructor(private responses: ModelResponse[]) {}
  async invoke(_m: ConversationMessage[], _t: ToolSpec[]): Promise<ModelResponse> {
    const next = this.responses.shift();
    if (!next) throw new Error("scripted model exhausted");
    return next;
  }
}

describe("workspace tool flow", () => {
  it("a worker writes a real file through the tools and the run completes", async () => {
    // Arrange a real checkout root (no git needed — the tools only need
    // a step workspace rooted at a directory).
    const root = tmp("root");
    mkdirSync(path.join(root, "app"), { recursive: true });

    const stepWorkspace = {
      checkoutPath: root,
      workingPath: path.join(root, "app"),
      sourceSubPath: "app",
      sourceBranch: "main",
      targetBranch: "t",
    };
    // The inspector needs git; this test exercises the tool flow with the
    // inspector absent (workspace-only) to isolate the tool wiring.

    const factory = new WorkspaceToolFactory({ workspace: stepWorkspace });
    const coder = {
      name: "coder",
      modelCode: "work",
      capability: "C",
      allowedTools: ["write_file", "read_file"] as const,
      allowedCommands: [],
    };
    const tools = factory.resolve(coder as never);
    expect(tools.map((t) => t.name)).toEqual(["write_file", "read_file"]);

    // Worker: request write_file once, then finish.
    const worker = new ScriptedModel([
      {
        content: "",
        toolCalls: [
          {
            id: "w1",
            name: "write_file",
            args: { path: "out.ts", content: "export const ok = true;\n" },
          },
        ],
        usage: { promptTokens: 5, completionTokens: 2, totalTokens: 7 },
      },
      { content: "written", usage: { promptTokens: 3, completionTokens: 1, totalTokens: 4 } },
    ]);
    // Orchestrator: delegate once, then finish.
    const orch = new ScriptedModel([
      {
        content: "",
        toolCalls: [
          {
            id: "o1",
            name: "invoke_worker",
            args: { workerName: "coder", purpose: "IMPLEMENT", instruction: "write it" },
          },
        ],
        usage: { promptTokens: 10, completionTokens: 5, totalTokens: 15 },
      },
      { content: "done", usage: { promptTokens: 6, completionTokens: 2, totalTokens: 8 } },
    ]);

    const chatModelFactory = scriptedFactory({ "orch-model": orch, "worker-model": worker });
    const invoker = new WorkerInvoker({
      attemptOrdinal: 1,
      chatModelFactory,
      turnExecutor: new TurnExecutor({}),
      toolFactory: async (w) => factory.resolve(w),
    });
    const runner = new OrchestrationRunner({
      chatModelFactory,
      workerInvoker: invoker,
      turnExecutor: new TurnExecutor({}),
    });

    const result = await runner.run(
      {
        schemaVersion: "1.0",
        dispatch: {
          workflowId: "wf", runId: "run", stepId: "s", taskId: "t",
          attemptId: "a", attemptOrdinal: 1, dispatchId: "a",
        },
        models: [
          { code: "orch-model", provider: "stub", modelId: "orch-model", description: "o", apiEndpoint: null, credentialRef: null, parameters: {} },
          { code: "worker-model", provider: "stub", modelId: "worker-model", description: "w", apiEndpoint: null, credentialRef: null, parameters: {} },
        ],
        source: { repoUrl: "u", sourceBranch: "b", sourceBaseCommit: "a".repeat(40), targetBranch: "t", credentialRef: null },
        policy: {
          allowedTools: ["write_file", "read_file"],
          commandTemplates: {},
          requiredIsolation: "TRUSTED_PROCESS",
          approvalPolicy: {},
          gitPolicy: { allowCheckpoint: true, allowPush: false },
          workspaceRetentionSeconds: 3600,
        },
        step: {
          id: "s", name: "S", taskType: "ORCHESTRATOR", agentProfileCode: "p",
          dependsOn: [],
          retryPolicy: { maxRetries: 0, initialBackoffSeconds: 1, maxBackoffSeconds: 1 },
          orchestration: {
            modelCode: "orch-model", goal: "G", specPath: null, sourceSubPath: "app",
            workers: [{ name: "coder", modelCode: "worker-model", capability: "C", allowedTools: ["write_file", "read_file"], allowedCommands: [] }],
            checkpointStrategy: { mode: "ON_VERIFICATION_PASS", commitMessage: "m", pushToRemote: false, allowNoChanges: false },
            completionCriteria: { definitionOfDone: "D", requireVerificationBy: [] },
            budget: { maxTokens: 1000, maxWorkerCalls: 5, maxVerifierRejectionsPerAttempt: 2, maxOrchestratorIterations: 10, maxWorkerIterations: 5, onBudgetExceeded: "FAIL" },
          },
        },
      },
      { runId: "run" },
    );

    expect(result.status).toBe("COMPLETED");
    expect(result.workerCalls).toHaveLength(1);
    expect(result.workerCalls[0].status).toBe("COMPLETED");

    // The real file must exist with the exact content.
    const read = readFileTool({ workspace: stepWorkspace });
    const content = await read.invoke({ path: "out.ts" });
    expect(content).toBe("export const ok = true;\n");
  });

  it("runner-provided workspace wires tools through setToolFactory (adapter shape)", async () => {
    // Git-backed: exactly how the LocalScenarioAdapter constructs the
    // runner — workspace option on the runner, NO constructor toolFactory.
    const originDir = tmp("origin");
    const originUrl = originDir.replace(/\\/g, "/");
    await exec("git", ["init", "--bare", "-b", "main", originDir]);
    const seed = tmp("seed");
    await exec("git", ["init", "-b", "main", seed]);
    mkdirSync(path.join(seed, "app"), { recursive: true });
    writeFileSync(path.join(seed, "app", "README.md"), "seed\n");
    await exec("git", ["add", "."], { cwd: seed });
    await exec("git", ["-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "i"], { cwd: seed });
    await exec("git", ["push", originUrl, "main"], { cwd: seed });
    const { stdout } = await exec("git", ["rev-parse", "main"], { cwd: originDir });

    const manager = new GitWorkspaceManager(tmp("ws"));
    const checkout = await manager.acquire({
      repoUrl: originUrl,
      sourceBranch: "main",
      sourceBaseCommit: stdout.trim(),
      targetBranch: "feat/t",
      accessToken: "",
    });
    const scope = new GitWorkspaceScope();
    const stepWorkspace = scope.resolve(checkout, "app");

    // Worker: write one file, then finish.
    const worker = new ScriptedModel([
      {
        content: "",
        toolCalls: [
          { id: "w1", name: "write_file", args: { path: "src/hi.ts", content: "export const hi = 1;\n" } },
        ],
        usage: { promptTokens: 5, completionTokens: 2, totalTokens: 7 },
      },
      { content: "written", usage: { promptTokens: 3, completionTokens: 1, totalTokens: 4 } },
    ]);
    const orch = new ScriptedModel([
      {
        content: "",
        toolCalls: [
          { id: "o1", name: "invoke_worker", args: { workerName: "coder", purpose: "IMPLEMENT", instruction: "write it" } },
        ],
        usage: { promptTokens: 10, completionTokens: 5, totalTokens: 15 },
      },
      { content: "done", usage: { promptTokens: 6, completionTokens: 2, totalTokens: 8 } },
    ]);

    const chatModelFactory = scriptedFactory({ "orch-model": orch, "worker-model": worker });
    const runner = new OrchestrationRunner({
      chatModelFactory,
      workerInvoker: new WorkerInvoker({
        attemptOrdinal: 1,
        chatModelFactory,
        turnExecutor: new TurnExecutor({}),
      }),
      turnExecutor: new TurnExecutor({}),
      workspace: stepWorkspace,
    });

    const result = await runner.run(
      {
        schemaVersion: "1.0",
        dispatch: { workflowId: "wf", runId: "run", stepId: "s", taskId: "t", attemptId: "a", attemptOrdinal: 1, dispatchId: "a" },
        models: [
          { code: "orch-model", provider: "stub", modelId: "orch-model", description: "o", apiEndpoint: null, credentialRef: null, parameters: {} },
          { code: "worker-model", provider: "stub", modelId: "worker-model", description: "w", apiEndpoint: null, credentialRef: null, parameters: {} },
        ],
        source: { repoUrl: "u", sourceBranch: "b", sourceBaseCommit: "a".repeat(40), targetBranch: "t", credentialRef: null },
        policy: {
          allowedTools: ["write_file", "read_file"],
          commandTemplates: {},
          requiredIsolation: "TRUSTED_PROCESS",
          approvalPolicy: {},
          gitPolicy: { allowCheckpoint: true, allowPush: false },
          workspaceRetentionSeconds: 3600,
        },
        step: {
          id: "s", name: "S", taskType: "ORCHESTRATOR", agentProfileCode: "p",
          dependsOn: [],
          retryPolicy: { maxRetries: 0, initialBackoffSeconds: 1, maxBackoffSeconds: 1 },
          orchestration: {
            modelCode: "orch-model", goal: "G", specPath: null, sourceSubPath: "app",
            workers: [{ name: "coder", modelCode: "worker-model", capability: "C", allowedTools: ["write_file"], allowedCommands: [] }],
            checkpointStrategy: { mode: "ON_VERIFICATION_PASS", commitMessage: "m", pushToRemote: false, allowNoChanges: false },
            completionCriteria: { definitionOfDone: "D", requireVerificationBy: [] },
            budget: { maxTokens: 1000, maxWorkerCalls: 5, maxVerifierRejectionsPerAttempt: 2, maxOrchestratorIterations: 10, maxWorkerIterations: 5, onBudgetExceeded: "FAIL" },
          },
        },
      },
      { runId: "run" },
    );

    expect(result.status).toBe("COMPLETED");
    const written = path.join(stepWorkspace.workingPath, "src", "hi.ts");
    expect(() => readFileSync(written)).not.toThrow();

    await manager.release(checkout);
  });
});