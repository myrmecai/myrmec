// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * WorkspaceToolFactory (design §10.3): builds the net-new Agent-local
 * file tools for one worker invocation, scoped to the step workspace and
 * filtered to the worker's declared allowedTools. The engine-declared
 * session tool path is never involved.
 */
import type { Tool } from "../executor/types.js";
import type { WorkerAuthoring } from "../orchestration/types.js";
import type { StepWorkspace } from "../workspace/WorkspaceManager.js";
import {
  createDirectoryTool,
  listDirectoryTool,
  readFileTool,
  writeFileTool,
  type FileToolContext,
  type SpecificationIdentity,
} from "./fileTools.js";

export interface WorkspaceToolFactoryOptions {
  /** Build tools against this resolved step workspace. */
  workspace: StepWorkspace;
  /** The protected spec identity when the step declared a specPath. */
  specification?: SpecificationIdentity;
  /** Optional worker-call-scoped command tool (Feature 5 seam). */
  commandTool?: Tool;
}

export class WorkspaceToolFactory {
  private readonly ctx: FileToolContext;
  private readonly commandTool?: Tool;

  constructor(options: WorkspaceToolFactoryOptions) {
    this.ctx = {
      workspace: options.workspace,
      ...(options.specification ? { specification: options.specification } : {}),
    };
    this.commandTool = options.commandTool;
  }

  /**
   * Return only the tools the worker's allowedTools names — nothing else
   * exists on the model's tool list, so nothing else is callable.
   */
  resolve(worker: WorkerAuthoring): Tool[] {
    const available = new Map<string, () => Tool>([
      ["read_file", () => readFileTool(this.ctx)],
      ["list_directory", () => listDirectoryTool(this.ctx)],
      ["create_directory", () => createDirectoryTool(this.ctx)],
      ["write_file", () => writeFileTool(this.ctx)],
    ]);
    if (this.commandTool) {
      const commandTool = this.commandTool;
      available.set("execute_command", () => commandTool);
    }
    const tools: Tool[] = [];
    for (const name of worker.allowedTools) {
      const make = available.get(name);
      if (make) tools.push(make());
    }
    return tools;
  }
}