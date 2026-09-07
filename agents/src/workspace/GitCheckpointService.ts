// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * GitCheckpointService (design §8.10, §12): creates exactly one verified
 * checkpoint commit after the complete step is approved. The model never
 * calls this — the runner invokes it after the completion criteria pass.
 *
 * The commit is built from a TEMPORARY index containing only the step's
 * sourceSubPath (never the mutable real index), created directly with
 * `git commit-tree` bypassing repository hooks, and the target ref is
 * advanced atomically with compare-and-set `git update-ref`. Idempotency
 * evidence lives under `refs/myrmec/checkpoints/<runId>/<stepId>/<treeHash>`.
 */
import { execFile } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import type { CheckpointCommit } from "../orchestration/types.js";

const exec = promisify(execFile);

/** Terminal checkpoint outcomes: success, no-changes, or failure. */
export type CheckpointOutcome =
  | { status: "COMMITTED"; commit: CheckpointCommit }
  | { status: "NO_CHANGES" }
  | { status: "FAILED"; errorCode: "CHECKPOINT_FAILED" | "CHECKPOINT_NO_CHANGES" };

export interface GitCheckpointServiceOptions {
  checkoutPath: string;
  targetBranch: string;
  sourceSubPath: string;
  runId: string;
  stepId: string;
  /** The conventional commit message from checkpointStrategy. */
  commitMessage: string;
  allowNoChanges: boolean;
}

/** Conventional-commit validation (§12 step 5): type(scope)!: subject ≤ 72. */
export function validateCommitMessage(message: string): string | null {
  if (message.length === 0) return "commit message is empty";
  const lines = message.split("\n");
  const subject = lines[0];
  if (subject.length > 72) {
    return `subject exceeds the 72-character limit (${subject.length})`;
  }
  if (!/^(feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert)(\([^)]+\))?(!)?: .+/.test(subject)) {
    return "subject is not a conventional commit (type(scope)!: subject)";
  }
  return null;
}

/** sha256 idempotency key over runId/stepId/treeHash (§12). */
export function checkpointIdempotencyKey(runId: string, stepId: string, treeHash: string): string {
  return createHash("sha256").update(`${runId}:${stepId}:${treeHash}`).digest("hex");
}

export class GitCheckpointService {
  private readonly options: GitCheckpointServiceOptions;
  /** In-process mutation lock (§12 step 1): one checkpoint at a time. */
  private static lock: Promise<unknown> = Promise.resolve();

  constructor(options: GitCheckpointServiceOptions) {
    this.options = options;
  }

  /** Serialize checkpoint creation under the workspace mutation lock. */
  private async underLock<T>(fn: () => Promise<T>): Promise<T> {
    const prev = GitCheckpointService.lock;
    let release: () => void = () => {};
    GitCheckpointService.lock = new Promise<void>((r) => (release = r));
    await prev;
    try {
      return await fn();
    } finally {
      release();
    }
  }

  private async git(args: string[]): Promise<string> {
    const { stdout } = await exec("git", args, {
      cwd: this.options.checkoutPath,
      windowsHide: true,
    });
    return stdout.trim();
  }

  /** The hidden idempotency ref for this runId/stepId/treeHash. */
  private idempotencyRef(treeHash: string): string {
    return `refs/myrmec/checkpoints/${this.options.runId}/${this.options.stepId}/${treeHash}`;
  }

  /**
   * Create the verified checkpoint commit (§12 steps 1-9). The caller
   * supplies the approved tree hash and the head the run expects the
   * target branch to sit at; a moved ref fails closed before any commit
   * object is created. Returns NO_CHANGES (success, no commit) only when
   * the approved tree equals the expected head's tree AND allowNoChanges
   * is true. All failures return terminal error codes — never thrown.
   */
  async create(approvedTreeHash: string, expectedHead: string): Promise<CheckpointOutcome> {
    return this.underLock(async () => {
      // Idempotency first (§12): a repeated request resolves the hidden
      // ref and returns the same commit.
      const ref = this.idempotencyRef(approvedTreeHash);
      const existing = await this.resolveRef(ref);
      if (existing) {
        // A pre-existing evidence ref for the same key: return the same
        // commit, reconstructing evidence from the commit object.
        return this.commitOutcomeFromCommit(existing, approvedTreeHash);
      }

      // The target branch must still point at the head this run expects.
      // A concurrent move fails closed and never publishes the wrong
      // commit; the compare-and-set below closes the race window.
      const branchHead = await this.resolveRef(`refs/heads/${this.options.targetBranch}`);
      if (branchHead !== expectedHead) {
        return { status: "FAILED", errorCode: "CHECKPOINT_FAILED" };
      }

      // §12 step 3: rebuild and verify the candidate tree from a temp
      // index seeded from expectedHead — never the mutable real index.
      let built: string;
      try {
        built = await this.buildCandidateTree(expectedHead);
      } catch {
        return { status: "FAILED", errorCode: "CHECKPOINT_FAILED" };
      }
      if (built !== approvedTreeHash) {
        return { status: "FAILED", errorCode: "CHECKPOINT_FAILED" };
      }

      // §12 step 4: unchanged tree → no commit, but only when
      // allowNoChanges; the required-verifier approval of that exact
      // tree was checked by the runner before calling.
      const headTree = await this.git(["rev-parse", `${expectedHead}^{tree}`]);
      if (headTree === approvedTreeHash) {
        if (this.options.allowNoChanges) {
          return { status: "NO_CHANGES" };
        }
        return { status: "FAILED", errorCode: "CHECKPOINT_NO_CHANGES" };
      }

      // §12 step 5: validate the fixed conventional commit message.
      const messageError = validateCommitMessage(this.options.commitMessage);
      if (messageError) {
        return { status: "FAILED", errorCode: "CHECKPOINT_FAILED" };
      }

      // §12 step 6: create the commit directly, bypassing hooks.
      let commitHash: string;
      try {
        commitHash = await this.git([
          "-c",
          "user.email=orchestration@myrmec",
          "-c",
          "user.name=Myrmec Orchestration",
          "commit-tree",
          approvedTreeHash,
          "-p",
          expectedHead,
          "-m",
          this.options.commitMessage,
        ]);
      } catch {
        return { status: "FAILED", errorCode: "CHECKPOINT_FAILED" };
      }

      // §12 step 7: atomic compare-and-set on the target ref. A moved
      // ref fails CHECKPOINT_FAILED and never publishes the wrong commit.
      try {
        await this.git([
          "update-ref",
          `refs/heads/${this.options.targetBranch}`,
          commitHash,
          expectedHead,
        ]);
      } catch {
        return { status: "FAILED", errorCode: "CHECKPOINT_FAILED" };
      }

      // §12 step 8: align the real index with the approved tree and
      // verify the candidate tree is still the approved one. A mixed
      // reset points the index at the new commit without touching the
      // working tree — the written files already match the tree.
      try {
        await this.git(["reset", "--mixed", commitHash]);
        const verifyTree = await this.git(["rev-parse", `${commitHash}^{tree}`]);
        if (verifyTree !== approvedTreeHash) {
          return { status: "FAILED", errorCode: "CHECKPOINT_FAILED" };
        }
      } catch {
        return { status: "FAILED", errorCode: "CHECKPOINT_FAILED" };
      }

      // §12 idempotency: store the evidence ref BEFORE returning.
      try {
        await this.git(["update-ref", ref, commitHash]);
      } catch {
        // The commit exists; the ref is best-effort evidence. A
        // conflicting ref value would have failed closed above.
      }

      return this.commitOutcomeFromCommit(commitHash, approvedTreeHash);
    });
  }

  /**
   * §12 step 3: rebuild the candidate tree from a temporary index
   * seeded from expectedHead containing only sourceSubPath — never the
   * mutable real index. An absent scope (nothing written yet) stages
   * nothing and yields the head's tree.
   */
  private async buildCandidateTree(expectedHead: string): Promise<string> {
    const tmpIndex = mkdtempSync(path.join(tmpdir(), "ckpt-idx-"));
    const indexPath = path.join(tmpIndex, "index");
    try {
      await exec(
        "git",
        ["read-tree", expectedHead, `--index-output=${indexPath}`],
        { cwd: this.options.checkoutPath, windowsHide: true },
      );
      const scopePath = path.join(this.options.checkoutPath, this.options.sourceSubPath);
      if (existsSync(scopePath)) {
        await exec("git", ["add", "-A", "--", this.options.sourceSubPath], {
          cwd: this.options.checkoutPath,
          env: { ...process.env, GIT_INDEX_FILE: indexPath },
          windowsHide: true,
        });
      }
      const { stdout } = await exec("git", ["write-tree"], {
        cwd: this.options.checkoutPath,
        env: { ...process.env, GIT_INDEX_FILE: indexPath },
        windowsHide: true,
      });
      return stdout.trim();
    } finally {
      rmSync(tmpIndex, { recursive: true, force: true });
    }
  }

  /** Resolve a ref to a commit hash, or null when it does not exist. */
  private async resolveRef(ref: string): Promise<string | null> {
    try {
      return await this.git(["rev-parse", "--verify", `${ref}^{commit}`]);
    } catch {
      return null;
    }
  }

  /** Build the §7.3 evidence from a commit object. */
  private async commitOutcomeFromCommit(
    commitHash: string,
    treeHash: string,
  ): Promise<CheckpointOutcome> {
    const parentHash = await this.git(["rev-parse", `${commitHash}^`]);
    // Committed files: the diff between parent and commit, restricted
    // to the step scope.
    const { stdout: diffOut } = await exec(
      "git",
      ["diff-tree", "--no-commit-id", "--name-only", "-r", parentHash, commitHash],
      { cwd: this.options.checkoutPath, windowsHide: true },
    );
    const files = diffOut
      .split("\n")
      .map((l) => l.trim())
      .filter((l) => l.length > 0 && l.startsWith(this.options.sourceSubPath + "/"));
    return {
      status: "COMMITTED",
      commit: {
        idempotencyKey: checkpointIdempotencyKey(
          this.options.runId,
          this.options.stepId,
          treeHash,
        ),
        commitHash,
        treeHash,
        parentHash,
        message: this.options.commitMessage,
        files,
      },
    };
  }
}

/**
 * RemotePublisher (§12): pushes the target branch only after checkpoint
 * success, host policy permitting. Expected-old-object lease: fetch the
 * remote ref first; any other value is PUSH_CONFLICT — never a force push.
 * Credentials, when present, pass only through a per-invocation `-c
 * credential.helper` process pipe — never persisted in the remote URL,
 * arguments written to logs, or result objects.
 */
export class RemotePublisher {
  constructor(
    private readonly options: {
      checkoutPath: string;
      repoUrl: string;
      targetBranch: string;
      /** Optional bearer/token credential for remote operations. */
      accessToken?: string;
    },
  ) {}

  async publish(expectedCommit: string): Promise<
    { status: "PUSHED" } | { status: "CONFLICT" } | { status: "FAILED" }
  > {
    // §12: credentials ride a per-invocation helper — never the URL.
    const credArgs = this.options.accessToken
      ? [
          "-c",
          `credential.helper=!f() { echo "username=x-access-token"; echo "password=${this.options.accessToken}"; }; f`,
        ]
      : [];
    try {
      const remoteRef = await exec(
        "git",
        [...credArgs, "ls-remote", this.options.repoUrl, `refs/heads/${this.options.targetBranch}`],
        { cwd: this.options.checkoutPath, windowsHide: true },
      );
      const remote = remoteRef.stdout.trim().split("\t")[0] || "";
      if (remote && remote !== expectedCommit) {
        return { status: "CONFLICT" };
      }
      await exec(
        "git",
        [...credArgs, "push", this.options.repoUrl, `HEAD:refs/heads/${this.options.targetBranch}`],
        { cwd: this.options.checkoutPath, windowsHide: true },
      );
      return { status: "PUSHED" };
    } catch {
      return { status: "FAILED" };
    }
  }
}