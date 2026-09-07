// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * File-tool contract tests (plan Feature 4): confinement through the
 * actual tools, bounded behavior, atomic writes, and spec protection.
 */
import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { mkdtempSync, rmSync, mkdirSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { createHash } from "node:crypto";
import {
  readFileTool,
  listDirectoryTool,
  createDirectoryTool,
  writeFileTool,
} from "./fileTools.js";
import type { StepWorkspace } from "../workspace/WorkspaceManager.js";

let root: string;

beforeEach(() => {
  root = mkdtempSync(path.join(tmpdir(), "filetools-"));
});

afterEach(() => {
  rmSync(root, { recursive: true, force: true });
});

function workspace(sub = "app"): StepWorkspace {
  mkdirSync(path.join(root, sub), { recursive: true });
  return {
    checkoutPath: root,
    workingPath: path.join(root, sub),
    sourceSubPath: sub,
    sourceBranch: "main",
    targetBranch: "t",
  };
}

describe("file tools", () => {
  it("write_file creates a file inside the workspace and read_file reads it back", async () => {
    const ws = workspace();
    const write = writeFileTool({ workspace: ws });
    const read = readFileTool({ workspace: ws });

    const result = await write.invoke({ path: "src/hello.ts", content: "export const hi = 1;\n" });
    expect(result).toContain("wrote src/hello.ts");
    const content = await read.invoke({ path: "src/hello.ts" });
    expect(content).toBe("export const hi = 1;\n");
  });

  it("write_file rejects traversal outside the workspace", async () => {
    const ws = workspace();
    const write = writeFileTool({ workspace: ws });
    await expect(write.invoke({ path: "../escape.txt", content: "x" })).rejects.toThrow(
      /PATH_OUTSIDE_WORKSPACE/,
    );
  });

  it("write_file rejects absolute paths", async () => {
    const ws = workspace();
    const write = writeFileTool({ workspace: ws });
    await expect(
      write.invoke({ path: "/etc/passwd", content: "x" }),
    ).rejects.toThrow(/PATH_OUTSIDE_WORKSPACE/);
  });

  it("read_file rejects traversal outside the workspace", async () => {
    const ws = workspace();
    writeFileSync(path.join(root, "outside.txt"), "secret");
    const read = readFileTool({ workspace: ws });
    await expect(read.invoke({ path: "../outside.txt" })).rejects.toThrow(
      /PATH_OUTSIDE_WORKSPACE/,
    );
  });

  it("list_directory lists bounded entries with directory markers", async () => {
    const ws = workspace();
    mkdirSync(path.join(root, "app", "a"));
    writeFileSync(path.join(root, "app", "a", "one.txt"), "1");
    const list = listDirectoryTool({ workspace: ws });
    const out = await list.invoke({ path: "a" });
    expect(out).toContain("one.txt");
  });

  it("create_directory creates parents and stays inside the workspace", async () => {
    const ws = workspace();
    const mkdir = createDirectoryTool({ workspace: ws });
    const result = await mkdir.invoke({ path: "deep/nested/dir" });
    expect(result).toContain("created deep/nested/dir");
    await expect(mkdir.invoke({ path: "../outside" })).rejects.toThrow(
      /PATH_OUTSIDE_WORKSPACE/,
    );
  });

  it("write_file denies mutating the protected specification", async () => {
    const ws = workspace();
    const specRel = "docs/requirements.md";
    const specAbs = path.join(root, "app", specRel);
    mkdirSync(path.dirname(specAbs), { recursive: true });
    const content = "# Requirements\nDo the thing.\n";
    writeFileSync(specAbs, content);
    const spec = {
      canonicalPath: specAbs,
      sha256: createHash("sha256").update(content).digest("hex"),
      byteLength: Buffer.byteLength(content),
    };
    const write = writeFileTool({ workspace: ws, specification: spec });
    await expect(
      write.invoke({ path: specRel, content: "tampered" }),
    ).rejects.toThrow(/protected/);
    // Reading the spec is still allowed.
    const read = readFileTool({ workspace: ws, specification: spec });
    const back = await read.invoke({ path: specRel });
    expect(back).toContain("# Requirements");
  });

  it("read_file reports oversized files instead of loading them", async () => {
    const ws = workspace();
    const big = Buffer.alloc(3 * 1024 * 1024, 65); // 3MB > 2MB cap
    const target = path.join(root, "app", "big.txt");
    writeFileSync(target, big);
    const read = readFileTool({ workspace: ws });
    const out = await read.invoke({ path: "big.txt" });
    expect(String(out)).toContain("read limit");
  });
});