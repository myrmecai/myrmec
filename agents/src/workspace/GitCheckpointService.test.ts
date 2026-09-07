// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * GitCheckpointService tests (plan Feature 6): verified changed tree,
 * unverified/no-changes handling, repeated request idempotency, moved
 * target ref, conventional-commit validation, scope-only commits, and
 * the RemotePublisher lease semantics.
 */
import { describe, it, expect, afterEach } from "vitest";
import { execFile } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import {
  GitCheckpointService,
  RemotePublisher,
  validateCommitMessage,
} from "./GitCheckpointService.js";

const exec = promisify(execFile);

let dirs: string[] = [];
function tmp(name: string): string {
  const d = mkdtempSync(path.join(tmpdir(), `ckpt-${name}-`));
  dirs.push(d);
  return d;
}
afterEach(() => {
  for (const d of dirs) rmSync(d, { recursive: true, force: true });
  dirs = [];
});

interface Fixture {
  originUrl: string;
  originDir: string;
  checkoutPath: string;
  headCommit: string;
}

/** Bare origin + one commit + a cloned checkout on the target branch. */
async function fixture(): Promise<Fixture> {
  const originDir = tmp("origin");
  const originUrl = originDir.replace(/\\/g, "/");
  await exec("git", ["init", "--bare", "-b", "main", originDir]);

  const seed = tmp("seed");
  await exec("git", ["init", "-b", "main", seed]);
  writeFileSync(path.join(seed, "README.md"), "# origin\n");
  await exec("git", ["add", "."], { cwd: seed });
  await exec("git", ["-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "init"], { cwd: seed });
  await exec("git", ["push", originUrl, "main"], { cwd: seed });

  const checkoutPath = tmp("checkout");
  await exec("git", ["clone", originUrl, checkoutPath]);
  await exec("git", ["checkout", "-b", "feat/test"], { cwd: checkoutPath });
  const { stdout } = await exec("git", ["rev-parse", "HEAD"], { cwd: checkoutPath });
  return { originUrl, originDir, checkoutPath, headCommit: stdout.trim() };
}

function service(
  fx: Fixture,
  over: Partial<ConstructorParameters<typeof GitCheckpointService>[0]> = {},
) {
  return new GitCheckpointService({
    checkoutPath: fx.checkoutPath,
    targetBranch: "feat/test",
    sourceSubPath: "app",
    runId: "run-1",
    stepId: "step-1",
    commitMessage: "feat: checkpoint",
    allowNoChanges: false,
    ...over,
  });
}

/** The approved tree hash for the current working tree of the scope. */
async function approvedTreeOf(fx: Fixture): Promise<string> {
  const idx = mkdtempSync(path.join(tmpdir(), "ckpt-idx-"));
  const indexPath = path.join(idx, "index");
  try {
    await exec("git", ["read-tree", "HEAD", `--index-output=${indexPath}`], { cwd: fx.checkoutPath });
    if (existsSync(path.join(fx.checkoutPath, "app"))) {
      await exec("git", ["add", "-A", "--", "app"], {
        cwd: fx.checkoutPath,
        env: { ...process.env, GIT_INDEX_FILE: indexPath },
      });
    }
    const { stdout } = await exec("git", ["write-tree"], {
      cwd: fx.checkoutPath,
      env: { ...process.env, GIT_INDEX_FILE: indexPath },
    });
    return stdout.trim();
  } finally {
    rmSync(idx, { recursive: true, force: true });
  }
}

describe("validateCommitMessage", () => {
  it("accepts conventional commits within the subject limit", () => {
    expect(validateCommitMessage("feat: checkpoint")).toBeNull();
    expect(validateCommitMessage("fix(engine): repair the loop")).toBeNull();
    expect(validateCommitMessage("chore!: breaking change")).toBeNull();
  });

  it("rejects non-conventional or oversized subjects", () => {
    expect(validateCommitMessage("just a message")).toBeTruthy();
    expect(validateCommitMessage("update stuff")).toBeTruthy();
    expect(validateCommitMessage(`feat: ${"x".repeat(80)}`)).toBeTruthy();
    expect(validateCommitMessage("")).toBeTruthy();
  });
});

describe("GitCheckpointService", () => {
  it("commits a verified changed tree on the target branch", async () => {
    const fx = await fixture();
    mkdirSync(path.join(fx.checkoutPath, "app", "src"), { recursive: true });
    writeFileSync(path.join(fx.checkoutPath, "app", "src", "main.ts"), "export {};\n");
    const tree = await approvedTreeOf(fx);

    const outcome = await service(fx).create(tree, fx.headCommit);
    expect(outcome.status).toBe("COMMITTED");
    if (outcome.status !== "COMMITTED") return;
    expect(outcome.commit.treeHash).toBe(tree);
    expect(outcome.commit.parentHash).toBe(fx.headCommit);
    expect(outcome.commit.message).toBe("feat: checkpoint");
    expect(outcome.commit.files).toEqual(["app/src/main.ts"]);
    // The target branch advanced to the new commit.
    const branch = await exec("git", ["rev-parse", "feat/test"], { cwd: fx.checkoutPath });
    expect(branch.stdout.trim()).toBe(outcome.commit.commitHash);
    // The commit message matches exactly.
    const log = await exec("git", ["log", "-1", "--pretty=%s"], { cwd: fx.checkoutPath });
    expect(log.stdout.trim()).toBe("feat: checkpoint");
    // No push: the origin does not have the target branch.
    const remote = await exec("git", ["ls-remote", fx.originUrl, "refs/heads/feat/test"], { cwd: fx.checkoutPath });
    expect(remote.stdout.trim()).toBe("");
  });

  it("fails with CHECKPOINT_NO_CHANGES for an unchanged tree without allowNoChanges", async () => {
    const fx = await fixture();
    const tree = await approvedTreeOf(fx); // nothing written: equals HEAD tree

    const outcome = await service(fx).create(tree, fx.headCommit);
    expect(outcome).toEqual({ status: "FAILED", errorCode: "CHECKPOINT_NO_CHANGES" });
  });

  it("returns NO_CHANGES success for an unchanged tree with allowNoChanges", async () => {
    const fx = await fixture();
    const tree = await approvedTreeOf(fx);
    const outcome = await service(fx, { allowNoChanges: true }).create(tree, fx.headCommit);
    expect(outcome).toEqual({ status: "NO_CHANGES" });
    // No commit was created.
    const head = await exec("git", ["rev-parse", "HEAD"], { cwd: fx.checkoutPath });
    expect(head.stdout.trim()).toBe(fx.headCommit);
  });

  it("a repeated request returns the same commit from the idempotency ref", async () => {
    const fx = await fixture();
    mkdirSync(path.join(fx.checkoutPath, "app", "src"), { recursive: true });
    writeFileSync(path.join(fx.checkoutPath, "app", "src", "main.ts"), "export {};\n");
    const tree = await approvedTreeOf(fx);

    const svc = service(fx);
    const first = await svc.create(tree, fx.headCommit);
    expect(first.status).toBe("COMMITTED");
    const second = await svc.create(tree, fx.headCommit);
    expect(second.status).toBe("COMMITTED");
    if (first.status !== "COMMITTED" || second.status !== "COMMITTED") return;
    expect(second.commit.commitHash).toBe(first.commit.commitHash);
    // Exactly one new commit exists.
    const count = await exec("git", ["rev-list", "--count", "HEAD"], { cwd: fx.checkoutPath });
    expect(Number(count.stdout.trim())).toBe(2);
  });

  it("fails closed when the target ref moved before the checkpoint", async () => {
    const fx = await fixture();
    mkdirSync(path.join(fx.checkoutPath, "app", "src"), { recursive: true });
    writeFileSync(path.join(fx.checkoutPath, "app", "src", "main.ts"), "export {};\n");
    const tree = await approvedTreeOf(fx);

    // Move the target branch underneath the expected head.
    await exec("git", ["commit", "--allow-empty", "-m", "interloper"], {
      cwd: fx.checkoutPath,
      env: { ...process.env, GIT_AUTHOR_NAME: "t", GIT_AUTHOR_EMAIL: "t@t", GIT_COMMITTER_NAME: "t", GIT_COMMITTER_EMAIL: "t@t" },
    });

    const outcome = await service(fx).create(tree, fx.headCommit);
    expect(outcome).toEqual({ status: "FAILED", errorCode: "CHECKPOINT_FAILED" });
  });

  it("rejects a non-conventional commit message", async () => {
    const fx = await fixture();
    mkdirSync(path.join(fx.checkoutPath, "app"), { recursive: true });
    writeFileSync(path.join(fx.checkoutPath, "app", "a.txt"), "a\n");
    const tree = await approvedTreeOf(fx);

    const outcome = await service(fx, { commitMessage: "not conventional" }).create(tree, fx.headCommit);
    expect(outcome).toEqual({ status: "FAILED", errorCode: "CHECKPOINT_FAILED" });
  });

  it("commits only files inside the step scope (sourceSubPath)", async () => {
    const fx = await fixture();
    mkdirSync(path.join(fx.checkoutPath, "app"), { recursive: true });
    mkdirSync(path.join(fx.checkoutPath, "outside"), { recursive: true });
    writeFileSync(path.join(fx.checkoutPath, "app", "in.ts"), "in\n");
    writeFileSync(path.join(fx.checkoutPath, "outside", "out.ts"), "out\n");
    const tree = await approvedTreeOf(fx);

    const outcome = await service(fx).create(tree, fx.headCommit);
    expect(outcome.status).toBe("COMMITTED");
    if (outcome.status !== "COMMITTED") return;
    expect(outcome.commit.files).toEqual(["app/in.ts"]);
    // The outside file is NOT in the commit.
    const inCommit = await exec(
      "git",
      ["diff-tree", "--no-commit-id", "--name-only", "-r", outcome.commit.commitHash],
      { cwd: fx.checkoutPath },
    );
    expect(inCommit.stdout.trim()).toBe("app/in.ts");
  });
});

describe("RemotePublisher", () => {
  it("pushes after checkpoint success when the remote ref is absent", async () => {
    const fx = await fixture();
    mkdirSync(path.join(fx.checkoutPath, "app"), { recursive: true });
    writeFileSync(path.join(fx.checkoutPath, "app", "p.ts"), "p\n");
    const tree = await approvedTreeOf(fx);
    const outcome = await service(fx).create(tree, fx.headCommit);
    expect(outcome.status).toBe("COMMITTED");
    if (outcome.status !== "COMMITTED") return;

    const publisher = new RemotePublisher({
      checkoutPath: fx.checkoutPath,
      repoUrl: fx.originUrl,
      targetBranch: "feat/test",
    });
    const push = await publisher.publish(outcome.commit.commitHash);
    expect(push.status).toBe("PUSHED");
    const remote = await exec("git", ["ls-remote", fx.originUrl, "refs/heads/feat/test"], { cwd: fx.checkoutPath });
    expect(remote.stdout.trim()).toContain(outcome.commit.commitHash);
  });

  it("returns CONFLICT when the remote ref differs from the expected commit", async () => {
    const fx = await fixture();
    const publisher = new RemotePublisher({
      checkoutPath: fx.checkoutPath,
      repoUrl: fx.originUrl,
      targetBranch: "feat/test",
    });
    // The remote has some other value for the target branch.
    await exec("git", ["push", fx.originUrl, "main:refs/heads/feat/test"], { cwd: fx.checkoutPath });
    const push = await publisher.publish(fx.headCommit.replace(/./g, "a"));
    expect(push.status).toBe("CONFLICT");
  });
});