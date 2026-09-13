// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Workspace-backed session tools (the conversation/inference path).
 *
 * `WorkspaceToolFactory` builds these tools for the *orchestration* path,
 * where a step workspace carries git branches and a checkout. A conversational
 * session has no such workspace — it has a single root (the host's open folder)
 * — so this adapts the same primitives (`fileTools.ts` + `confinePath`) to a
 * plain root.
 *
 * Every path is confined to the root by `confinePath`, which rejects absolute
 * paths, `..` traversal, and symlink escapes. That confinement is the reason
 * this lives in the SDK rather than being reimplemented by a host: duplicating
 * path-safety logic is exactly the kind of drift that leads to escapes.
 *
 * Deliberately excludes `execute_command` (arbitrary process execution is a
 * separate policy decision) and the destructive/git tools.
 */
import type { Tool } from "./types.js";
import type { FileToolContext } from "../tools/fileTools.js";
import {
  createDirectoryTool,
  listDirectoryTool,
  readFileTool,
  writeFileTool,
} from "../tools/fileTools.js";

/** Tool names this module can build, in the order they are offered. */
export const WORKSPACE_SESSION_TOOL_NAMES = [
  "read_file",
  "list_directory",
  "write_file",
  "create_directory",
] as const;

export type WorkspaceSessionToolName =
  (typeof WORKSPACE_SESSION_TOOL_NAMES)[number];

/**
 * Build the workspace-backed file tools for a session, scoped to `rootPath`.
 *
 * @param rootPath   the workspace root every path is confined to
 * @param onlyNames  optional filter (e.g. from a policy); when omitted all
 *                   four tools are returned
 */
export function createWorkspaceSessionTools(
  rootPath: string,
  onlyNames?: readonly string[],
): Tool[] {
  // `StepWorkspace` is the shape fileTools expects; a conversational session
  // only has `workingPath` (the root). The remaining fields are unused by the
  // tools, which is why this adapter is safe.
  const workspace = {
    checkoutPath: rootPath,
    workingPath: rootPath,
    sourceSubPath: "",
    sourceBranch: "",
    targetBranch: "",
  };
  const ctx: FileToolContext = { workspace };

  const builders: Record<WorkspaceSessionToolName, () => Tool> = {
    read_file: () => readFileTool(ctx),
    list_directory: () => listDirectoryTool(ctx),
    write_file: () => writeFileTool(ctx),
    create_directory: () => createDirectoryTool(ctx),
  };

  const wanted = onlyNames
    ? WORKSPACE_SESSION_TOOL_NAMES.filter((n) => onlyNames.includes(n))
    : [...WORKSPACE_SESSION_TOOL_NAMES];

  return wanted.map((name) => builders[name]());
}
