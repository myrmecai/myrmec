// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * GitWorkspaceInspector tests (design §8.7): the candidate tree comes from
 * a temporary index seeded from HEAD + the step scope only; the real
 * index is never touched; no-op writes do not change the tree.
 */
import { describe, it, expect, afterEach } from "vitest";
import { execFile } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync, mkdirSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import { GitWorkspaceManager, GitWorkspaceScope } from "./GitWorkspaceManager.js";
import { GitWorkspaceInspector } from "./WorkspaceInspector.js";

const exec = promisify(execFile);

let dirs: string[] = [];

function tmp(name: string): string {
  const d = mkdtempSync(path.join(tmpdir(), `insp-${name}-`));
  dirs.push(d);
  return d;
}

afterEach(() => {
  for (const d of dirs) rmSync(d, { recursive: true, force: true });
  dirs = [];
});

async function makeOriginWithScope(): Promise<{
  originUrl: string;
  headCommit: string;
}> {
  const originDir = tmp("origin");
  const originUrl = originDir.replace(/\\/g, "/");
  await exec("git", ["init", "--bare", "-b", "main", originDir]);
  const seed = tmp("seed");
  await exec("git", ["init", "-b", "main", seed]);
  mkdirSync(path.join(seed, "app"), { recursive: true });
  writeFileSync(path.join(seed, "app", "README.md"), "seed\n");
  await exec("git", ["add", "."], { cwd: seed });
  await exec("git", ["-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "init"], {
    cwd: seed,
  });
  await exec("git", ["push", originUrl, "main"], { cwd: seed });
  const { stdout } = await exec("git", ["rev-parse", "main"], { cwd: originDir });
  return { originUrl, headCommit: stdout.trim() };
}

describe("GitWorkspaceInspector", () => {
  it("reports a clean tree when the scope is untouched", async () => {
    const { originUrl, headCommit } = await makeOriginWithScope();
    const manager = new GitWorkspaceManager(tmp("ws"));
    const checkout = await manager.acquire({
      repoUrl: originUrl,
      sourceBranch: "main",
      sourceBaseCommit: headCommit,
      targetBranch: "feat/t",
      accessToken: "",
    });
    const scope = new GitWorkspaceScope();
    const ws = scope.resolve(checkout, "app");
    const inspector = new GitWorkspaceInspector();

    const before = await inspector.inspect(ws);
    expect(before.clean).toBe(true);
    expect(before.changedFiles).toEqual([]);

    await manager.release(checkout);
  });

  it("detects a new file as a tree change with changed paths", async () => {
    const { originUrl, headCommit } = await makeOriginWithScope();
    const manager = new GitWorkspaceManager(tmp("ws"));
    const checkout = await manager.acquire({
      repoUrl: originUrl,
      sourceBranch: "main",
      sourceBaseCommit: headCommit,
      targetBranch: "feat/t",
      accessToken: "",
    });
    const scope = new GitWorkspaceScope();
    const ws = scope.resolve(checkout, "app");
    const inspector = new GitWorkspaceInspector();

    writeFileSync(path.join(ws.workingPath, "new.ts"), "export const x = 1;\n");
    const after = await inspector.inspect(ws);
    expect(after.clean).toBe(false);
    expect(after.changedFiles.some((f) => f.endsWith("app/new.ts"))).toBe(true);

    await manager.release(checkout);
  });

  it("keeps a stable tree hash for identical re-writes (no-op)", async () => {
    const { originUrl, headCommit } = await makeOriginWithScope();
    const manager = new GitWorkspaceManager(tmp("ws"));
    const checkout = await manager.acquire({
      repoUrl: originUrl,
      sourceBranch: "main",
      sourceBaseCommit: headCommit,
      targetBranch: "feat/t",
      accessToken: "",
    });
    const scope = new GitWorkspaceScope();
    const ws = scope.resolve(checkout, "app");
    const inspector = new GitWorkspaceInspector();

    writeFileSync(path.join(ws.workingPath, "same.ts"), "stable\n");
    const first = await inspector.inspect(ws);
    // Re-write identical content: the tree hash must not change.
    writeFileSync(path.join(ws.workingPath, "same.ts"), "stable\n");
    const second = await inspector.inspect(ws);
    expect(second.treeHash).toBe(first.treeHash);

    await manager.release(checkout);
  });

  it("never touches the real git index", async () => {
    const { originUrl, headCommit } = await makeOriginWithScope();
    const manager = new GitWorkspaceManager(tmp("ws"));
    const checkout = await manager.acquire({
      repoUrl: originUrl,
      sourceBranch: "main",
      sourceBaseCommit: headCommit,
      targetBranch: "feat/t",
      accessToken: "",
    });
    const scope = new GitWorkspaceScope();
    const ws = scope.resolve(checkout, "app");
    const inspector = new GitWorkspaceInspector();

    writeFileSync(path.join(ws.workingPath, "touched.ts"), "t\n");
    await inspector.inspect(ws);

    // The real index must not know about the new file.
    const { stdout } = await exec("git", ["status", "--porcelain"], {
      cwd: checkout.checkoutPath,
    });
    expect(stdout).toContain("app/touched.ts"); // untracked/modified on disk
    const indexFiles = await exec("git", ["ls-files", "--", "app"], {
      cwd: checkout.checkoutPath,
    });
    expect(indexFiles.stdout).not.toContain("touched.ts");

    await manager.release(checkout);
  });

  it("tolerates a scope directory that does not exist yet", async () => {
    const { originUrl, headCommit } = await makeOriginWithScope();
    const manager = new GitWorkspaceManager(tmp("ws"));
    const checkout = await manager.acquire({
      repoUrl: originUrl,
      sourceBranch: "main",
      sourceBaseCommit: headCommit,
      targetBranch: "feat/t",
      accessToken: "",
    });
    const scope = new GitWorkspaceScope();
    const ws = scope.resolve(checkout, "does/not/exist");
    const inspector = new GitWorkspaceInspector();
    const result = await inspector.inspect(ws);
    expect(result.clean).toBe(true);
    expect(result.changedFiles).toEqual([]);

    await manager.release(checkout);
  });

  it("detects files created in a scope that did not exist at checkout", async () => {
    // The scenario shape: the fixture seeds only README.md; the worker
    // creates app/src/*.ts AFTER checkout through the tools.
    const { originUrl, headCommit } = await makeOriginWithScope();
    const manager = new GitWorkspaceManager(tmp("ws"));
    const checkout = await manager.acquire({
      repoUrl: originUrl,
      sourceBranch: "main",
      sourceBaseCommit: headCommit,
      targetBranch: "feat/t",
      accessToken: "",
    });
    const scope = new GitWorkspaceScope();
    const ws = scope.resolve(checkout, "app");
    const inspector = new GitWorkspaceInspector();

    // Baseline: scope absent.
    const baseline = await inspector.inspect(ws);
    expect(baseline.clean).toBe(true);

    // Worker-style writes: create the scope through nested directories.
    mkdirSync(path.join(ws.workingPath, "src"), { recursive: true });
    writeFileSync(path.join(ws.workingPath, "src", "hello.ts"), "export const hello = 'world';\n");
    writeFileSync(path.join(ws.workingPath, "src", "util.ts"), "export const util = () => 42;\n");

    const after = await inspector.inspect(ws);
    expect(after.clean).toBe(false);
    expect(after.changedFiles.length).toBe(2);
    expect(after.changedFiles.some((f) => f.endsWith("app/src/hello.ts"))).toBe(true);

    await manager.release(checkout);
  });
});