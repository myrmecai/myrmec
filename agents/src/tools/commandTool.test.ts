// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * execute_command contract tests (design §10.3): policy intersection,
 * parameter validation, cwd confinement, git refusal, bounded output,
 * and evidence records.
 */
import { describe, it, expect, afterEach } from "vitest";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import type { CommandExecutionRecord, CommandTemplateDefinition } from "../orchestration/types.js";
import { executeCommandTool } from "./commandTool.js";

let dirs: string[] = [];
function tmp(name: string): string {
  const d = mkdtempSync(path.join(tmpdir(), `cmd-${name}-`));
  dirs.push(d);
  return d;
}
afterEach(() => {
  for (const d of dirs) rmSync(d, { recursive: true, force: true });
  dirs = [];
});

function template(over: Partial<CommandTemplateDefinition> = {}): CommandTemplateDefinition {
  return {
    executable: "node",
    args: ["-e", "console.log('hello from command')"],
    parameters: {},
    cwdPattern: ".",
    environmentAllowlist: [],
    timeoutSeconds: 30,
    maxOutputBytes: 1024 * 1024,
    network: "DENY",
    maxCpuSeconds: 10,
    maxMemoryBytes: 256 * 1024 * 1024,
    riskClass: "SAFE",
    ...over,
  };
}

function makeTool(options: {
  templates?: Record<string, CommandTemplateDefinition>;
  allowed?: string[];
  onRecord?: (r: CommandExecutionRecord) => void;
}) {
  const records: CommandExecutionRecord[] = [];
  const root = tmp("root");
  mkdirRoot(root);
  const templates = options.templates ?? { echo: template() };
  const tool = executeCommandTool({
    workspace: {
      checkoutPath: root,
      workingPath: root,
      sourceSubPath: ".",
      sourceBranch: "main",
      targetBranch: "t",
    },
    commandTemplates: templates,
    allowedCommands: options.allowed ?? Object.keys(templates),
    workerCallId: "call-1",
    recordExecution: (r) => {
      records.push(r);
      options.onRecord?.(r);
    },
  });
  return { tool, records, root };
}

function mkdirRoot(root: string): void {
  writeFileSync(path.join(root, "marker.txt"), "x");
}

describe("execute_command", () => {
  it("runs an allowed template and records evidence", async () => {
    const { tool, records } = makeTool({});
    const result = (await tool.invoke({
      templateName: "echo",
      parameters: {},
      cwd: ".",
    })) as Record<string, unknown>;
    expect(result.status).toBe("OK");
    expect(String(result.stdout)).toContain("hello from command");
    expect(records).toHaveLength(1);
    expect(records[0].templateName).toBe("echo");
    expect(records[0].exitCode).toBe(0);
    expect(records[0].cancelled).toBe(false);
    expect(records[0].timedOut).toBe(false);
  });

  it("rejects a template not in the worker's allowedCommands", async () => {
    const { tool, records } = makeTool({ allowed: ["other"] });
    const result = (await tool.invoke({
      templateName: "echo",
      parameters: {},
      cwd: ".",
    })) as Record<string, unknown>;
    expect(result.error).toBe("COMMAND_NOT_ALLOWED");
    expect(records).toHaveLength(0);
  });

  it("rejects a template absent from the policy commandTemplates", async () => {
    const { tool } = makeTool({ templates: {} });
    const result = (await tool.invoke({
      templateName: "echo",
      parameters: {},
      cwd: ".",
    })) as Record<string, unknown>;
    expect(result.error).toBe("COMMAND_NOT_ALLOWED");
  });

  it("rejects git as an executable regardless of policy", async () => {
    const { tool } = makeTool({
      templates: { gitcmd: template({ executable: "git", args: ["status"] }) },
    });
    const result = (await tool.invoke({
      templateName: "gitcmd",
      parameters: {},
      cwd: ".",
    })) as Record<string, unknown>;
    expect(result.error).toBe("COMMAND_NOT_ALLOWED");
  });

  it("validates parameters: required, type, pattern, allowedValues, unknown", async () => {
    const { tool } = makeTool({
      templates: {
        greet: template({
          executable: "node",
          args: ["-e", "console.log('x')"],
          parameters: {
            name: { type: "string", required: true },
            count: { type: "integer", required: false },
            force: { type: "boolean", required: false },
          },
        }),
      },
    });
    // missing required
    let r = (await tool.invoke({ templateName: "greet", parameters: {}, cwd: "." })) as Record<string, unknown>;
    expect(r.error).toBe("COMMAND_PARAMETER_ERROR");
    // wrong type
    r = (await tool.invoke({ templateName: "greet", parameters: { name: "x", count: "3" }, cwd: "." })) as Record<string, unknown>;
    expect(r.error).toBe("COMMAND_PARAMETER_ERROR");
    // unknown parameter
    r = (await tool.invoke({ templateName: "greet", parameters: { name: "x", extra: 1 }, cwd: "." })) as Record<string, unknown>;
    expect(r.error).toBe("COMMAND_PARAMETER_ERROR");
    // valid
    r = (await tool.invoke({ templateName: "greet", parameters: { name: "x", count: 3, force: true }, cwd: "." })) as Record<string, unknown>;
    expect(r.status).toBe("OK");
  });

  it("rejects a cwd escaping the workspace", async () => {
    const { tool } = makeTool({});
    const result = (await tool.invoke({
      templateName: "echo",
      parameters: {},
      cwd: "../escape",
    })) as Record<string, unknown>;
    expect(result.error).toBe("COMMAND_CWD_OUTSIDE_WORKSPACE");
  });

  it("substitutes { parameter } placeholders into the command args", async () => {
    const { tool } = makeTool({
      templates: {
        greet: template({
          executable: "node",
          args: ["-e", "console.log('hi ' + process.argv[1])", { parameter: "name" }],
          parameters: { name: { type: "string", required: true } },
        }),
      },
    });
    const result = (await tool.invoke({
      templateName: "greet",
      parameters: { name: "myrmec" },
      cwd: ".",
    })) as Record<string, unknown>;
    expect(result.status).toBe("OK");
    expect(String(result.stdout)).toContain("hi myrmec");
  });

  it("reports a non-zero exit code as FAILED with evidence", async () => {
    const { tool, records } = makeTool({
      templates: {
        fail: template({
          executable: "node",
          args: ["-e", "process.exit(3)"],
        }),
      },
    });
    const result = (await tool.invoke({
      templateName: "fail",
      parameters: {},
      cwd: ".",
    })) as Record<string, unknown>;
    expect(result.status).toBe("FAILED");
    expect(result.exitCode).toBe(3);
    expect(records[0].exitCode).toBe(3);
  });

  it("kills the process tree on timeout and records timedOut", async () => {
    const { tool, records } = makeTool({
      templates: {
        slow: template({
          executable: "node",
          args: ["-e", "setTimeout(() => {}, 60000)"],
          timeoutSeconds: 1,
        }),
      },
    });
    const started = Date.now();
    const result = (await tool.invoke({
      templateName: "slow",
      parameters: {},
      cwd: ".",
    })) as Record<string, unknown>;
    expect(result.status).toBe("TIMED_OUT");
    expect(Date.now() - started).toBeLessThan(10000);
    expect(records[0].timedOut).toBe(true);
    expect(records[0].exitCode).toBe(null);
  });

  it("truncates output beyond maxOutputBytes", async () => {
    const { tool, records } = makeTool({
      templates: {
        noisy: template({
          executable: "node",
          args: ["-e", "console.log('x'.repeat(5000))"],
          maxOutputBytes: 100,
        }),
      },
    });
    const result = (await tool.invoke({
      templateName: "noisy",
      parameters: {},
      cwd: ".",
    })) as Record<string, unknown>;
    expect(result.outputTruncated).toBe(true);
    expect(records[0].outputTruncated).toBe(true);
  });

  it("reports a spawn failure (missing executable) without throwing", async () => {
    const { tool } = makeTool({
      templates: {
        ghost: template({ executable: "definitely-not-a-real-exe-12345" }),
      },
    });
    const result = (await tool.invoke({
      templateName: "ghost",
      parameters: {},
      cwd: ".",
    })) as Record<string, unknown>;
    expect(result.status).toBe("FAILED");
    expect(result.error).toBeTruthy();
  });
});