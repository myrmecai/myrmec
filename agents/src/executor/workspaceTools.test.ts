// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * The conversational-session workspace tools (design §10.3 primitives applied
 * to a plain session root). These tests pin the two properties that matter:
 * the tools work against the root, and they refuse any path outside it.
 */
import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { mkdtempSync, mkdirSync, rmSync, writeFileSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import {
  createWorkspaceSessionTools,
  WORKSPACE_SESSION_TOOL_NAMES,
} from "./workspaceTools.js";
import type { Tool } from "./types.js";

function byName(tools: Tool[]): Map<string, Tool> {
  return new Map(tools.map((t) => [t.name, t]));
}

describe("createWorkspaceSessionTools", () => {
  let root: string;

  beforeEach(() => {
    root = mkdtempSync(path.join(tmpdir(), "myrmec-session-tools-"));
    mkdirSync(path.join(root, "src"), { recursive: true });
    writeFileSync(path.join(root, "src", "hello.txt"), "HELLO", "utf-8");
  });

  afterEach(() => {
    rmSync(root, { recursive: true, force: true });
  });

  it("offers all four file tools by default", () => {
    const tools = createWorkspaceSessionTools(root);
    expect(tools.map((t) => t.name).sort()).toEqual(
      [...WORKSPACE_SESSION_TOOL_NAMES].sort(),
    );
  });

  it("filters to only the requested tool names", () => {
    const tools = createWorkspaceSessionTools(root, ["read_file"]);
    expect(tools.map((t) => t.name)).toEqual(["read_file"]);
  });

  it("reads a file relative to the root", async () => {
    const read = byName(createWorkspaceSessionTools(root)).get("read_file")!;
    await expect(read.invoke({ path: "src/hello.txt" })).resolves.toBe("HELLO");
  });

  it("lists a directory relative to the root", async () => {
    const list = byName(createWorkspaceSessionTools(root)).get("list_directory")!;
    await expect(list.invoke({ path: "src" })).resolves.toBe("hello.txt");
  });

  it("writes a file atomically inside the root", async () => {
    const write = byName(createWorkspaceSessionTools(root)).get("write_file")!;
    await write.invoke({ path: "src/new/out.txt", content: "WRITTEN" });
    expect(existsSync(path.join(root, "src", "new", "out.txt"))).toBe(true);
  });

  // ── Confinement: the security property that must never regress ──

  it("rejects relative traversal outside the root", async () => {
    const read = byName(createWorkspaceSessionTools(root)).get("read_file")!;
    await expect(read.invoke({ path: "../../outside.txt" })).rejects.toThrow(
      /PATH_OUTSIDE_WORKSPACE/,
    );
  });

  it("rejects absolute paths", async () => {
    const read = byName(createWorkspaceSessionTools(root)).get("read_file")!;
    await expect(read.invoke({ path: "C:/Windows/win.ini" })).rejects.toThrow(
      /PATH_OUTSIDE_WORKSPACE/,
    );
  });

  it("rejects writing outside the root", async () => {
    const write = byName(createWorkspaceSessionTools(root)).get("write_file")!;
    await expect(
      write.invoke({ path: "../escaped.txt", content: "nope" }),
    ).rejects.toThrow(/PATH_OUTSIDE_WORKSPACE/);
  });
});
