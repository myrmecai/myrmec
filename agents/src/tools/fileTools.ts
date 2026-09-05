// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * The four Agent-local file tools (design §10.3): every path is resolved
 * relative to the step's `workingPath` and canonicalized inside it. These
 * are net-new implementations behind WorkerToolFactory — never aliases
 * for engine-declared session tools.
 */
import { promises as fs } from "node:fs";
import path from "node:path";
import { randomUUID } from "node:crypto";
import type { Tool } from "../executor/types.js";
import { WorkspaceError, confinePath } from "../workspace/GitWorkspaceManager.js";
import type { StepWorkspace } from "../workspace/WorkspaceManager.js";

/** Bounded defaults (design §10.3): read/list caps keep model context
 * bounded; atomic writes use a temp-then-rename so a crash cannot leave
 * a half-written file. */
const MAX_READ_BYTES = 2 * 1024 * 1024;
const MAX_LIST_ENTRIES = 1000;

/** A protected specification identity (design §5 step 4): every mutation
 * whose canonical destination aliases this path is denied. */
export interface SpecificationIdentity {
  canonicalPath: string;
  sha256: string;
  byteLength: number;
}

/** Context the tools close over: the step workspace plus the optional
 * protected spec path and its digest. */
export interface FileToolContext {
  workspace: StepWorkspace;
  specification?: SpecificationIdentity;
}

/** The canonical path a tool operation would target — throws
 * PATH_OUTSIDE_WORKSPACE for escapes (WorkspaceError). */
function target(ctx: FileToolContext, userPath: string): string {
  return confinePath(ctx.workspace.workingPath, userPath);
}

/** Deny any mutation aliasing the protected specification (design §10.3:
 * SPECIFICATION_MUTATED is the terminal failure for digests that DID
 * change; alias denial is the preventive side). */
function assertNotSpecification(ctx: FileToolContext, canonicalTarget: string): void {
  if (
    ctx.specification &&
    path.resolve(canonicalTarget) === path.resolve(ctx.specification.canonicalPath)
  ) {
    throw new WorkspaceError(
      "WORKSPACE_ERROR",
      "the specification file is protected and cannot be mutated",
    );
  }
}

export function readFileTool(ctx: FileToolContext): Tool {
  return {
    name: "read_file",
    description: "Read a text file inside the step workspace. Paths are relative to the step root.",
    parameters: {
      type: "object",
      properties: { path: { type: "string" } },
      required: ["path"],
    },
    async invoke(args) {
      const t = target(ctx, String(args.path));
      const stat = await fs.stat(t);
      if (stat.size > MAX_READ_BYTES) {
        return `error: file exceeds the ${MAX_READ_BYTES}-byte read limit`;
      }
      const content = await fs.readFile(t, "utf-8");
      return content;
    },
  };
}

export function listDirectoryTool(ctx: FileToolContext): Tool {
  return {
    name: "list_directory",
    description: "List entries of a directory inside the step workspace.",
    parameters: {
      type: "object",
      properties: { path: { type: "string" } },
      required: ["path"],
    },
    async invoke(args) {
      const t = target(ctx, String(args.path));
      const entries = await fs.readdir(t, { withFileTypes: true });
      const bounded = entries.slice(0, MAX_LIST_ENTRIES);
      if (bounded.length < entries.length) {
        return `warning: listing truncated at ${MAX_LIST_ENTRIES} entries\n` +
          bounded.map((e) => `${e.name}${e.isDirectory() ? "/" : ""}`).join("\n");
      }
      return bounded.map((e) => `${e.name}${e.isDirectory() ? "/" : ""}`).join("\n");
    },
  };
}

export function createDirectoryTool(ctx: FileToolContext): Tool {
  return {
    name: "create_directory",
    description: "Create a directory (and parents) inside the step workspace.",
    parameters: {
      type: "object",
      properties: { path: { type: "string" } },
      required: ["path"],
    },
    async invoke(args) {
      const t = target(ctx, String(args.path));
      assertNotSpecification(ctx, t);
      await fs.mkdir(t, { recursive: true });
      return `created ${String(args.path)}`;
    },
  };
}

export function writeFileTool(ctx: FileToolContext): Tool {
  return {
    name: "write_file",
    description:
      "Write a file inside the step workspace atomically (temp-then-rename). Paths are relative to the step root.",
    parameters: {
      type: "object",
      properties: {
        path: { type: "string" },
        content: { type: "string" },
      },
      required: ["path", "content"],
    },
    async invoke(args) {
      const t = target(ctx, String(args.path));
      assertNotSpecification(ctx, t);
      await fs.mkdir(path.dirname(t), { recursive: true });
      // Atomic replace: write a sibling temp file, then rename over the
      // target — a crash mid-write can never leave truncated content.
      const tmpPath = `${t}.tmp-${randomUUID()}`;
      await fs.writeFile(tmpPath, String(args.content), "utf-8");
      await fs.rename(tmpPath, t);
      return `wrote ${String(args.path)} (${String(args.content).length} bytes)`;
    },
  };
}