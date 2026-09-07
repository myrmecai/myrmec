// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * execute_command (design §10.3): the fifth Agent-local worker tool.
 * Accepts structured input `{ templateName, parameters, cwd }`, resolves
 * the pinned command template from the assignment's ExecutionPolicy
 * (intersection: worker.allowedCommands ∩ policy.commandTemplates), and
 * spawns with `shell: false`, a sanitized environment, bounded output,
 * a deadline, and descendant-process-tree kill on timeout.
 *
 * `git` is never a valid executable (policy never includes it) — the model
 * cannot cause commits, pushes, or history rewrites through this tool.
 */
import { spawn } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import path from "node:path";
import type { Tool } from "../executor/types.js";
import type {
  CommandTemplateDefinition,
  CommandExecutionRecord,
} from "../orchestration/types.js";
import { WorkspaceError, confinePath } from "../workspace/GitWorkspaceManager.js";
import type { StepWorkspace } from "../workspace/WorkspaceManager.js";

/** The structured tool input (design §10.3). */
export interface ExecuteCommandInput {
  templateName: string;
  parameters: Record<string, string | number | boolean>;
  cwd: string;
}

/** Context the command tool closes over. */
export interface CommandToolContext {
  workspace: StepWorkspace;
  /** The templates the assignment policy carries (already the
   * referenced-subset compiled for this step). */
  commandTemplates: Record<string, CommandTemplateDefinition>;
  /** The worker's declared command allowlist to intersect with. */
  allowedCommands: string[];
  /** Collects CommandExecutionRecord evidence for the run result. */
  recordExecution: (record: CommandExecutionRecord) => void;
  /** Worker call id binding the evidence records. */
  workerCallId: string;
  /** Cooperative cancellation (Feature 7 wires a real signal). */
  cancelled?: () => boolean;
}

/** Terminal for a rejected command — carried in the tool result, not
 * thrown (the turn loop feeds tool errors back to the model). */
export interface CommandToolError {
  error: "COMMAND_NOT_ALLOWED" | "COMMAND_PARAMETER_ERROR" | "COMMAND_CWD_OUTSIDE_WORKSPACE";
  message: string;
}

/** Validate parameters against the template schema (§10.3): type,
 * required, pattern, allowedValues. Returns an error object or null. */
function validateParameters(
  template: CommandTemplateDefinition,
  parameters: Record<string, unknown>,
): CommandToolError | null {
  for (const [name, spec] of Object.entries(template.parameters)) {
    const value = parameters[name];
    if (value === undefined) {
      if (spec.required) {
        return {
          error: "COMMAND_PARAMETER_ERROR",
          message: `missing required parameter: ${name}`,
        };
      }
      continue;
    }
    if (spec.type === "string" && typeof value !== "string") {
      return { error: "COMMAND_PARAMETER_ERROR", message: `parameter ${name} must be a string` };
    }
    if (spec.type === "integer" && !(typeof value === "number" && Number.isInteger(value))) {
      return { error: "COMMAND_PARAMETER_ERROR", message: `parameter ${name} must be an integer` };
    }
    if (spec.type === "boolean" && typeof value !== "boolean") {
      return { error: "COMMAND_PARAMETER_ERROR", message: `parameter ${name} must be a boolean` };
    }
    if (spec.pattern !== undefined && typeof value === "string") {
      if (!new RegExp(spec.pattern).test(value)) {
        return {
          error: "COMMAND_PARAMETER_ERROR",
          message: `parameter ${name} does not match the allowed pattern`,
        };
      }
    }
    if (spec.allowedValues !== undefined && !spec.allowedValues.includes(value as never)) {
      return {
        error: "COMMAND_PARAMETER_ERROR",
        message: `parameter ${name} is not in the allowed values`,
      };
    }
  }
  // Unknown parameters are rejected too — the schema is closed.
  for (const name of Object.keys(parameters)) {
    if (!(name in template.parameters)) {
      return { error: "COMMAND_PARAMETER_ERROR", message: `unknown parameter: ${name}` };
    }
  }
  return null;
}

/** Build the argv from the template's args array, substituting
 * `{ parameter }` placeholders. */
function buildArgs(
  template: CommandTemplateDefinition,
  parameters: Record<string, string | number | boolean>,
): string[] {
  return template.args.map((a) => {
    if (typeof a === "string") return a;
    const value = parameters[a.parameter];
    return value === undefined ? "" : String(value);
  });
}

export function executeCommandTool(ctx: CommandToolContext): Tool {
  return {
    name: "execute_command",
    description:
      "Run one allowed command template inside the step workspace. " +
      "Input: templateName, parameters, cwd (workspace-relative).",
    parameters: {
      type: "object",
      properties: {
        templateName: { type: "string" },
        parameters: { type: "object", additionalProperties: true },
        cwd: { type: "string" },
      },
      required: ["templateName", "parameters", "cwd"],
    },
    async invoke(rawArgs) {
      const args = rawArgs as unknown as ExecuteCommandInput;

      // Policy intersection (§10.3): the worker's declared commands AND
      // the assignment policy's templates.
      if (
        typeof args.templateName !== "string" ||
        !ctx.allowedCommands.includes(args.templateName) ||
        !ctx.commandTemplates[args.templateName]
      ) {
        return {
          error: "COMMAND_NOT_ALLOWED",
          message: `command template not allowed: ${String(args.templateName)}`,
        } satisfies CommandToolError;
      }
      const template = ctx.commandTemplates[args.templateName]!;

      // git is never a valid executable — belt and braces over policy.
      const exe = template.executable;
      const exeBase = path.basename(exe).replace(/\.exe$/i, "").toLowerCase();
      if (exeBase === "git") {
        return {
          error: "COMMAND_NOT_ALLOWED",
          message: "git is not an allowed command executable",
        } satisfies CommandToolError;
      }

      if (!args.parameters || typeof args.parameters !== "object") {
        return {
          error: "COMMAND_PARAMETER_ERROR",
          message: "parameters must be an object",
        } satisfies CommandToolError;
      }
      const paramError = validateParameters(template, args.parameters);
      if (paramError) return paramError;

      // cwd must resolve inside the step workspace (§10.3).
      if (typeof args.cwd !== "string" || args.cwd.length === 0) {
        return {
          error: "COMMAND_CWD_OUTSIDE_WORKSPACE",
          message: "cwd must be a non-empty workspace-relative path",
        } satisfies CommandToolError;
      }
      let cwdAbs: string;
      try {
        cwdAbs = confinePath(ctx.workspace.workingPath, args.cwd);
      } catch (err) {
        if (err instanceof WorkspaceError) {
          return {
            error: "COMMAND_CWD_OUTSIDE_WORKSPACE",
            message: `cwd escapes the step workspace: ${String(args.cwd)}`,
          } satisfies CommandToolError;
        }
        throw err;
      }

      const argv = [exe, ...buildArgs(template, args.parameters)];
      const argsDigest = createHash("sha256")
        .update(argv.slice(1).join("\u0000"))
        .digest("hex")
        .slice(0, 32);
      const commandId = randomUUID();

      // Sanitized environment: only the allowlisted variables survive.
      const env: Record<string, string> = {};
      for (const name of template.environmentAllowlist) {
        const v = process.env[name];
        if (v !== undefined) env[name] = v;
      }

      return await new Promise<Record<string, unknown>>((resolve) => {
        const child = spawn(exe, argv.slice(1), {
          cwd: cwdAbs,
          env,
          shell: false,
          windowsHide: true,
          detached: process.platform !== "win32",
          stdio: ["ignore", "pipe", "pipe"],
        });

        let stdout: Buffer = Buffer.alloc(0);
        let stderr: Buffer = Buffer.alloc(0);
        let truncated = false;
        let settled = false;
        const capture = (
          stream: NodeJS.ReadableStream,
          store: () => Buffer,
          set: (b: Buffer) => void,
        ) => {
          stream.on("data", (chunk: Buffer) => {
            const next = Buffer.concat([store(), chunk]);
            if (next.length > template.maxOutputBytes) {
              truncated = true;
              set(next.subarray(0, template.maxOutputBytes));
            } else {
              set(next);
            }
          });
        };
        capture(child.stdout!, () => stdout, (b) => (stdout = b));
        capture(child.stderr!, () => stderr, (b) => (stderr = b));

        let timedOut = false;
        let cancelled = false;
        const timer = setTimeout(
          () => {
            timedOut = true;
            killTree(child.pid);
          },
          template.timeoutSeconds * 1000,
        );

        const cancelCheck = setInterval(() => {
          if (ctx.cancelled?.()) {
            cancelled = true;
            killTree(child.pid);
            finish(null, true);
          }
        }, 50);
        const finish = (code: number | null, force: boolean) => {
          if (settled) return;
          settled = true;
          clearTimeout(timer);
          clearInterval(cancelCheck);
          if (force) child.removeAllListeners("close");
          const record: CommandExecutionRecord = {
            commandId,
            workerCallId: ctx.workerCallId,
            templateName: args.templateName,
            executable: exe,
            argsDigest,
            workspaceRelativeCwd: args.cwd,
            // Timeout and cancellation are not process outcomes: the
            // kill is ours, so the exit code is null in the evidence.
            exitCode: cancelled || timedOut ? null : code,
            timedOut,
            cancelled,
            outputTruncated: truncated,
          };
          ctx.recordExecution(record);
          const outText = stdout.toString("utf-8");
          const errText = stderr.toString("utf-8");
          if (cancelled) {
            resolve({ status: "CANCELLED", exitCode: null, outputTruncated: truncated });
            return;
          }
          if (timedOut) {
            resolve({
              status: "TIMED_OUT",
              exitCode: null,
              outputTruncated: truncated,
              ...(errText ? { stderr: errText.slice(0, 2000) } : {}),
            });
            return;
          }
          resolve({
            status: code === 0 ? "OK" : "FAILED",
            exitCode: code,
            stdout: outText.slice(0, Math.min(outText.length, 20000)),
            ...(stderr.length > 0 ? { stderr: errText.slice(0, 2000) } : {}),
            outputTruncated: truncated,
          });
        };

        child.on("close", (code) => finish(code, false));
        child.on("error", (err) => {
          // Spawn failure (missing executable, EACCES): evidence with a
          // null exit code — never thrown (the loop feeds it back).
          if (settled) return;
          settled = true;
          clearTimeout(timer);
          clearInterval(cancelCheck);
          ctx.recordExecution({
            commandId,
            workerCallId: ctx.workerCallId,
            templateName: args.templateName,
            executable: exe,
            argsDigest,
            workspaceRelativeCwd: args.cwd,
            exitCode: null,
            timedOut: false,
            cancelled: false,
            outputTruncated: truncated,
          });
          resolve({ status: "FAILED", exitCode: null, error: String(err.message) });
        });
      });
    },
  };
}

/** Kill the complete descendant process tree (§10.3): on Windows,
 * `taskkill /T /F`; elsewhere a negative-pgid kill. */
function killTree(pid: number | undefined): void {
  if (pid === undefined) return;
  if (process.platform === "win32") {
    spawn("taskkill", ["/PID", String(pid), "/T", "/F"], {
      stdio: "ignore",
      windowsHide: true,
    });
  } else {
    try {
      process.kill(-pid, "SIGKILL");
    } catch {
      // Not a group leader; fall back to the direct kill.
      try {
        process.kill(pid, "SIGKILL");
      } catch {
        /* already gone */
      }
    }
  }
}