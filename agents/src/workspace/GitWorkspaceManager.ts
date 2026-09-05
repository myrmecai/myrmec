// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * GitWorkspaceManager (design §8.6): clones a repository, checks out the
 * exact assigned `sourceBaseCommit` object (never resolving the branch
 * again), creates the target branch, and returns a checkout-root handle.
 * All Git invocations use `execFile` with argument arrays and
 * `shell: false`. Credentials, when present, are passed only through a
 * per-invocation `-c credential.helper` process pipe — never persisted
 * in remotes, config, logs, or command lines.
 */
import { execFile } from "node:child_process";
import { rmSync, realpathSync, existsSync, mkdirSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { promisify } from "node:util";
import type {
  CheckoutHandle,
  StepWorkspace,
  WorkspaceManager,
  WorkspaceScope,
} from "./WorkspaceManager.js";
import type { ResolvedSource } from "../orchestration/types.js";

const exec = promisify(execFile);

/** Errors surfaced by the workspace layer. `SOURCE_BASE_UNAVAILABLE` is
 * terminal and distinct from generic `WORKSPACE_ERROR` (design §14). */
export class WorkspaceError extends Error {
  constructor(
    readonly code: "SOURCE_BASE_UNAVAILABLE" | "WORKSPACE_ERROR",
    message: string,
  ) {
    super(message);
    this.name = "WorkspaceError";
  }
}

/** Run one git command in `cwd` with argument array, `shell: false`. */
export async function git(
  cwd: string,
  args: string[],
  options?: { signal?: AbortSignal },
): Promise<string> {
  try {
    const { stdout } = await exec("git", args, {
      cwd,
      encoding: "utf-8",
      maxBuffer: 32 * 1024 * 1024,
      ...(options?.signal ? { signal: options.signal } : {}),
      windowsHide: true,
    });
    return stdout;
  } catch (err) {
    // Redact: never surface credential-bearing URLs or tokens from stderr.
    const safe =
      err instanceof Error
        ? err.message.replace(/(https?:\/\/)[^@/]+@/g, "$1***@").slice(0, 500)
        : String(err);
    throw new WorkspaceError("WORKSPACE_ERROR", `git ${args[0]} failed: ${safe}`);
  }
}

/**
 * Fetch the assigned source and materialize a working checkout on the
 * target branch rooted at exactly `sourceBaseCommit`.
 */
export class GitWorkspaceManager implements WorkspaceManager {
  constructor(private readonly workspaceRoot: string = path.join(tmpdir(), "myrmec-ws")) {}

  async acquire(source: ResolvedSource, signal?: AbortSignal): Promise<CheckoutHandle> {
    const workspaceId = `ws-${randomUUID()}`;
    const baseDir = path.join(this.workspaceRoot, workspaceId, "1");
    let checkoutPath: string;
    try {
      checkoutPath = path.join(baseDir, "checkout");
      mkdirSync(path.dirname(checkoutPath), { recursive: true });
      await mkdirSync(checkoutPath, { recursive: true });
    } catch (err) {
      throw new WorkspaceError("WORKSPACE_ERROR", `workspace create failed: ${String(err)}`);
    }

    // Credentials: per-invocation credential helper via stdin — the token
    // never appears in a command line, remote URL, or on-disk config.
    const credArgs = source.accessToken
      ? [
          "-c",
          `credential.helper=!f() { echo "username=x-access-token"; echo "password=${source.accessToken}"; }; f`,
        ]
      : [];

    try {
      // Clone without checking out (branch resolution is not trusted).
      await git(checkoutPath, [
        ...credArgs,
        "clone",
        "--no-checkout",
        "--filter=blob:none",
        source.repoUrl,
        ".",
      ], { signal });

      // Fetch the exact assigned object. A missing/unreachable object is
      // terminal SOURCE_BASE_UNAVAILABLE (design §14): the workflow cannot
      // safely reconstruct against its recorded source.
      try {
        await git(checkoutPath, [
          ...credArgs,
          "fetch",
          "--no-tags",
          "origin",
          source.sourceBaseCommit,
        ], { signal });
      } catch {
        throw new WorkspaceError(
          "SOURCE_BASE_UNAVAILABLE",
          `assigned source base commit could not be fetched: ${source.sourceBaseCommit.slice(0, 12)}`,
        );
      }

      // Detach to the exact object, then create the target branch at it.
      await git(checkoutPath, ["checkout", "--detach", source.sourceBaseCommit], { signal });
      await git(checkoutPath, ["checkout", "-B", source.targetBranch], { signal });
    } catch (err) {
      // Clean the half-built checkout on any failure.
      rmSync(path.dirname(checkoutPath), { recursive: true, force: true });
      throw err;
    }

    return {
      workspaceId,
      generation: 1,
      checkoutPath,
      sourceBranch: source.sourceBranch,
      targetBranch: source.targetBranch,
      baseCommit: source.sourceBaseCommit,
    };
  }

  async release(checkout: CheckoutHandle): Promise<void> {
    const genDir = path.dirname(checkout.checkoutPath);
    rmSync(genDir, { recursive: true, force: true });
  }
}

/**
 * Derive the step workspace and enforce path confinement (design §8.6):
 * the working path is `<checkout>/<sourceSubPath>` and every tool path is
 * resolved against it with traversal/absolute/symlink rejection.
 */
export class GitWorkspaceScope implements WorkspaceScope {
  resolve(checkout: CheckoutHandle, sourceSubPath: string): StepWorkspace {
    const checkoutPath = path.resolve(checkout.checkoutPath);
    const workingPath = path.resolve(checkoutPath, sourceSubPath);
    if (workingPath !== checkoutPath && !workingPath.startsWith(checkoutPath + path.sep)) {
      throw new WorkspaceError("WORKSPACE_ERROR", "sourceSubPath escapes the checkout");
    }
    return {
      checkoutPath,
      workingPath,
      sourceSubPath,
      sourceBranch: checkout.sourceBranch,
      targetBranch: checkout.targetBranch,
    };
  }
}

/**
 * Canonicalize a tool-supplied path against a permitted root (design
 * §10.3/§20.3): resolve relative to the root, reject absolute and `..`
 * traversal, then verify the FINAL canonical target (after symlinks) is
 * inside the root. Windows case-insensitive roots are compared via
 * case-normalized prefixes.
 */
export function confinePath(root: string, userPath: string): string {
  if (typeof userPath !== "string" || userPath.length === 0) {
    throw new WorkspaceError("WORKSPACE_ERROR", "PATH_OUTSIDE_WORKSPACE: empty path");
  }
  // Reject absolute paths immediately.
  if (path.isAbsolute(userPath.replace(/\\/g, "/"))) {
    throw new WorkspaceError("WORKSPACE_ERROR", `PATH_OUTSIDE_WORKSPACE: absolute path`);
  }
  const rootResolved = path.resolve(root);
  const joined = path.resolve(rootResolved, userPath.replace(/\\/g, "/"));

  // Case-insensitive containment on Windows-style roots: compare both the
  // literal and the lowercased prefix.
  const contained =
    joined === rootResolved || joined.startsWith(rootResolved + path.sep);
  if (!contained) {
    const normRoot = rootResolved.toLowerCase();
    const normJoined = joined.toLowerCase();
    const containedCI = normJoined === normRoot || normJoined.startsWith(normRoot + path.sep);
    if (!containedCI) {
      throw new WorkspaceError("WORKSPACE_ERROR", `PATH_OUTSIDE_WORKSPACE: traversal`);
    }
  }

  // Symlink escape: the canonical target (realpath) must stay inside the
  // canonical root. Paths that do not exist yet compare their nearest
  // EXISTING ancestor — the tool root may legitimately not exist before
  // the first write creates it.
  const nearestExisting = (p: string): string => {
    let probe = p;
    while (!existsSync(probe)) {
      const parent = path.dirname(probe);
      if (parent === probe) break;
      probe = parent;
    }
    return realpathSync(probe);
  };
  const canonicalRoot = nearestExisting(rootResolved);
  const canonicalTarget = nearestExisting(joined);
  const canonicalContained =
    canonicalTarget === canonicalRoot ||
    canonicalTarget.startsWith(canonicalRoot + path.sep);
  if (!canonicalContained) {
    throw new WorkspaceError("WORKSPACE_ERROR", `PATH_OUTSIDE_WORKSPACE: symlink escape`);
  }
  return joined;
}