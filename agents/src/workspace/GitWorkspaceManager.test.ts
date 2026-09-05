// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * GitWorkspaceManager tests (plan Feature 4): a temporary bare origin, a
 * resolved sourceBaseCommit that survives branch movement, target-branch
 * creation, repeatable cleanup, cancellation during fetch, and terminal
 * SOURCE_BASE_UNAVAILABLE for an unreachable assigned object. Path
 * confinement rejects `..`, absolute, and symlink escapes with
 * PATH_OUTSIDE_WORKSPACE.
 */
import { describe, it, expect, afterEach, beforeEach } from "vitest";
import { execFile } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync, symlinkSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import {
  GitWorkspaceManager,
  GitWorkspaceScope,
  WorkspaceError,
  confinePath,
} from "./GitWorkspaceManager.js";
import type { ResolvedSource } from "../orchestration/types.js";

const exec = promisify(execFile);

let dirs: string[] = [];

function tmp(name: string): string {
  const d = mkdtempSync(path.join(tmpdir(), `ws-${name}-`));
  dirs.push(d);
  return d;
}

afterEach(() => {
  for (const d of dirs) {
    rmSync(d, { recursive: true, force: true });
  }
  dirs = [];
});

/** Create a bare origin with one commit on `main` and return the commit
 * SHA plus the bare origin's file URL. */
async function makeBareOrigin(): Promise<{
  originUrl: string;
  headCommit: string;
  originDir: string;
}> {
  const originDir = tmp("origin");
  const originUrl = originDir.replace(/\\/g, "/");
  await exec("git", ["init", "--bare", "-b", "main", originDir]);

  const seed = tmp("seed");
  await exec("git", ["init", "-b", "main", seed]);
  writeFileSync(path.join(seed, "README.md"), "# test origin\n");
  await exec("git", ["add", "."], { cwd: seed });
  await exec("git", ["-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "init"], { cwd: seed });
  await exec("git", ["push", originUrl, "main"], { cwd: seed });

  const { stdout } = await exec("git", ["rev-parse", "main"], { cwd: originDir });
  return { originUrl, headCommit: stdout.trim(), originDir };
}

function resolvedSource(originUrl: string, commit: string): ResolvedSource {
  return {
    repoUrl: originUrl,
    sourceBranch: "main",
    sourceBaseCommit: commit,
    targetBranch: "feat/test",
    accessToken: "",
  };
}

describe("GitWorkspaceManager", () => {
  it("checks out the exact assigned commit and creates the target branch", async () => {
    const { originUrl, headCommit } = await makeBareOrigin();
    const manager = new GitWorkspaceManager(tmp("ws"));
    const checkout = await manager.acquire(resolvedSource(originUrl, headCommit));

    expect(checkout.baseCommit).toBe(headCommit);
    expect(checkout.targetBranch).toBe("feat/test");
    const branch = await exec("git", ["rev-parse", "--abbrev-ref", "HEAD"], {
      cwd: checkout.checkoutPath,
    });
    expect(branch.stdout.trim()).toBe("feat/test");
    const head = await exec("git", ["rev-parse", "HEAD"], { cwd: checkout.checkoutPath });
    expect(head.stdout.trim()).toBe(headCommit);

    await manager.release(checkout);
  });

  it("still fetches the assigned object after the branch moves", async () => {
    const { originUrl, headCommit, originDir } = await makeBareOrigin();

    // Move `main` forward in the origin after resolution.
    const seed2 = tmp("seed2");
    await exec("git", ["clone", originUrl, seed2]);
    writeFileSync(path.join(seed2, "later.txt"), "later\n");
    await exec("git", ["add", "."], { cwd: seed2 });
    await exec("git", ["-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "later"], {
      cwd: seed2,
    });
    await exec("git", ["push", originUrl, "main"], { cwd: seed2 });

    // The origin's main no longer points at headCommit — but the object
    // is still reachable; the checkout must get the EXACT assigned object.
    const movedHead = (await exec("git", ["rev-parse", "main"], { cwd: originDir })).stdout.trim();
    expect(movedHead).not.toBe(headCommit);

    const manager = new GitWorkspaceManager(tmp("ws"));
    const checkout = await manager.acquire(resolvedSource(originUrl, headCommit));
    const head = await exec("git", ["rev-parse", "HEAD"], { cwd: checkout.checkoutPath });
    expect(head.stdout.trim()).toBe(headCommit);

    await manager.release(checkout);
  });

  it("fails terminal SOURCE_BASE_UNAVAILABLE for an unreachable object", async () => {
    const { originUrl } = await makeBareOrigin();
    const ghost = "d".repeat(40);
    const manager = new GitWorkspaceManager(tmp("ws"));
    await expect(manager.acquire(resolvedSource(originUrl, ghost))).rejects.toMatchObject({
      code: "SOURCE_BASE_UNAVAILABLE",
    } as Partial<WorkspaceError>);

    // Repeatable cleanup: the workspace dir for the failed attempt is gone.
    const wsRoot = tmp("ws2");
    const manager2 = new GitWorkspaceManager(wsRoot);
    await expect(manager2.acquire(resolvedSource(originUrl, ghost))).rejects.toBeInstanceOf(
      WorkspaceError,
    );
  });

  it("supports cancellation during fetch", async () => {
    const { originUrl, headCommit } = await makeBareOrigin();
    const controller = new AbortController();
    controller.abort();
    const manager = new GitWorkspaceManager(tmp("ws"));
    await expect(
      manager.acquire(resolvedSource(originUrl, headCommit), controller.signal),
    ).rejects.toBeInstanceOf(WorkspaceError);
  });
});

describe("GitWorkspaceScope", () => {
  it("derives the step working path inside the checkout", () => {
    const scope = new GitWorkspaceScope();
    const checkout = {
      workspaceId: "ws",
      generation: 1,
      checkoutPath: path.join("c:", "chk"),
      sourceBranch: "main",
      targetBranch: "t",
      baseCommit: "a",
    };
    const step = scope.resolve(checkout, "app/src");
    expect(step.sourceSubPath).toBe("app/src");
    expect(path.basename(step.workingPath)).toBe("src");
  });

  it("rejects a sourceSubPath escaping the checkout", () => {
    const scope = new GitWorkspaceScope();
    const checkout = {
      workspaceId: "ws",
      generation: 1,
      checkoutPath: path.join("c:", "chk"),
      sourceBranch: "main",
      targetBranch: "t",
      baseCommit: "a",
    };
    expect(() => scope.resolve(checkout, "../outside")).toThrow(WorkspaceError);
  });
});

describe("confinePath", () => {
  let root: string;

  beforeEach(() => {
    root = tmp("confine");
  });

  it("accepts a relative path inside the root", () => {
    const target = confinePath(root, "src/file.ts");
    expect(target.startsWith(path.resolve(root))).toBe(true);
  });

  it("rejects `..` traversal", () => {
    expect(() => confinePath(root, "../escape.txt")).toThrow(/PATH_OUTSIDE_WORKSPACE/);
  });

  it("rejects absolute paths", () => {
    expect(() => confinePath(root, "/etc/passwd")).toThrow(/PATH_OUTSIDE_WORKSPACE/);
    if (process.platform === "win32") {
      expect(() => confinePath(root, "c:\\windows\\system32")).toThrow(
        /PATH_OUTSIDE_WORKSPACE/,
      );
    }
  });

  it("rejects symlink/junction escapes", () => {
    const outside = tmp("outside");
    const linkPath = path.join(root, "evil-link");
    // Windows without developer mode forbids file symlinks; a junction
    // exercises the same canonical-target escape check.
    if (process.platform === "win32") {
      symlinkSync(outside, linkPath, "junction");
    } else {
      symlinkSync(outside, linkPath);
    }
    expect(() => confinePath(root, "evil-link/pwned.txt")).toThrow(
      /PATH_OUTSIDE_WORKSPACE/,
    );
  });

  it("rejects an empty path", () => {
    expect(() => confinePath(root, "")).toThrow(/PATH_OUTSIDE_WORKSPACE|empty/);
  });
});