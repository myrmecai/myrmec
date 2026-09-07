// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * WorkspaceToolFactory (design §10.3): builds the net-new Agent-local
 * file tools for one worker invocation, scoped to the step workspace and
 * filtered to the worker's declared allowedTools. The engine-declared
 * session tool path is never involved.
 */
import type { Tool } from "../executor/types.js";
import type {
  WorkerAuthoring,
  CommandExecutionRecord,
  CommandTemplateDefinition,
} from "../orchestration/types.js";
import type { StepWorkspace } from "../workspace/WorkspaceManager.js";
import {
  createDirectoryTool,
  listDirectoryTool,
  readFileTool,
  writeFileTool,
  type FileToolContext,
  type SpecificationIdentity,
} from "./fileTools.js";
import { executeCommandTool } from "./commandTool.js";

export interface WorkspaceToolFactoryOptions {
  /** Build tools against this resolved step workspace. */
  workspace: StepWorkspace;
  /** The protected spec identity when the step declared a specPath. */
  specification?: SpecificationIdentity;
  /** Feature 5: the assignment's command templates (already the
   * referenced-subset) — needed to build execute_command. */
  commandTemplates?: Record<string, CommandTemplateDefinition>;
  /** Feature 5: collects CommandExecutionRecord evidence. */
  recordExecution?: (record: CommandExecutionRecord) => void;
  /** Feature 5: binds evidence records to the worker call id. */
  workerCallId?: string;
  /** Cooperative cancellation check (Feature 7 wires a real signal). */
  cancelled?: () => boolean;
}

export class WorkspaceToolFactory {
  private readonly ctx: FileToolContext;
  private readonly options: WorkspaceToolFactoryOptions;

  constructor(options: WorkspaceToolFactoryOptions) {
    this.options = options;
    this.ctx = {
      workspace: options.workspace,
      ...(options.specification ? { specification: options.specification } : {}),
    };
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
    // Feature 5 (design §10.3): execute_command exists only when the
    // assignment carried command templates and the worker declares
    // allowedCommands — the policy intersection happens inside the tool.
    if (this.options.commandTemplates && worker.allowedCommands.length > 0) {
      available.set(
        "execute_command",
        () =>
          executeCommandTool({
            workspace: this.options.workspace,
            commandTemplates: this.options.commandTemplates!,
            allowedCommands: worker.allowedCommands,
            recordExecution: this.options.recordExecution ?? (() => {}),
            workerCallId: this.options.workerCallId ?? "",
            ...(this.options.cancelled ? { cancelled: this.options.cancelled } : {}),
          }),
      );
    }
    const tools: Tool[] = [];
    for (const name of worker.allowedTools) {
      const make = available.get(name);
      if (make) tools.push(make());
    }
    return tools;
  }
}