// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * TargetBranchPusher (design 2026-10-01 unified session execution, D10
 * part 1): pushes the attempt's HEAD commit to the run's unique
 * engine-authored target branch BEFORE the terminal frame is sent
 * (protocol 9 order: the engine must be able to see the committed state
 * before it records the outcome).
 *
 * A push failure on the COMPLETE/PAUSED path converts the terminal to a
 * retryable `execution.failed` (`SOURCE_PUSH_FAILED`) - a run's committed
 * state is never reported as terminal success when the engine cannot
 * see it. Transient failures retry (3 attempts, 1s backoff); after the
 * last attempt the pusher throws {@link SourcePushError} carrying the
 * last stderr line.
 *
 * All git invocations use execFile argument arrays with shell: false.
 * Credentials, when present, pass only through a per-invocation
 * `-c credential.helper` process pipe - never persisted in remotes,
 * config, logs, or command lines.
 */
import { execFile } from "node:child_process";
import { promisify } from "node:util";

const exec = promisify(execFile);

/** Push retry policy: 3 attempts total, 1s between attempts. */
const MAX_ATTEMPTS = 3;
const BACKOFF_MS = 1_000;

/** Terminal push failure - the last stderr line rides the message. */
export class SourcePushError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "SourcePushError";
  }
}

/** Options for {@link TargetBranchPusher.push}. */
export interface TargetBranchPushOptions {
  /** The task-scoped checkout path whose HEAD is pushed. */
  checkoutPath: string;
  /** The run's unique engine-authored target branch (bare name). */
  targetBranch: string;
  /** Optional bearer/token credential for the remote (credential pipe). */
  accessToken?: string;
}

/** The exec seam a test can stub (defaults to node's execFile). */
export type GitExec = (
  args: string[],
  cwd: string,
) => Promise<{ stdout: string; stderr: string }>;

/** The delay seam a test can stub (defaults to setTimeout). */
export type Sleep = (ms: number) => Promise<void>;

const defaultExec: GitExec = async (args, cwd) => {
  const { stdout, stderr } = await exec("git", args, {
    cwd,
    encoding: "utf-8",
    maxBuffer: 32 * 1024 * 1024,
    windowsHide: true,
  });
  return { stdout, stderr };
};

const defaultSleep: Sleep = (ms) =>
  new Promise((resolve) => setTimeout(resolve, ms));

/** Extract the last non-empty stderr line (redacted, bounded). */
function lastStderrLine(stderr: string): string {
  const lines = stderr
    .split(/\r?\n/)
    .map((l) => l.trim())
    // Redact: never surface credential-bearing URLs or tokens.
    .map((l) => l.replace(/(https?:\/\/)[^@/]+@/g, "$1***@"));
  for (let i = lines.length - 1; i >= 0; i--) {
    if (lines[i]) return lines[i].slice(0, 500);
  }
  return "";
}

/**
 * Push HEAD to `origin refs/heads/<targetBranch>`.
 *
 * Retries transient failures up to 3 attempts with 1s backoff; throws
 * {@link SourcePushError} with the last stderr line when every attempt
 * fails. Never force-pushes and never touches any other ref.
 */
export class TargetBranchPusher {
  private readonly gitExec: GitExec;
  private readonly sleep: Sleep;

  constructor(seams?: { exec?: GitExec; sleep?: Sleep }) {
    this.gitExec = seams?.exec ?? defaultExec;
    this.sleep = seams?.sleep ?? defaultSleep;
  }

  async push(options: TargetBranchPushOptions): Promise<void> {
    const credArgs = options.accessToken
      ? [
          "-c",
          `credential.helper=!f() { echo "username=x-access-token"; echo "password=${options.accessToken}"; }; f`,
        ]
      : [];
    let lastError = "push failed";
    for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        await this.gitExec(
          [
            ...credArgs,
            "push",
            "origin",
            `HEAD:refs/heads/${options.targetBranch}`,
          ],
          options.checkoutPath,
        );
        return;
      } catch (err) {
        const stderr =
          err && typeof err === "object" && "stderr" in err
            ? String((err as { stderr: unknown }).stderr)
            : err instanceof Error
              ? err.message
              : String(err);
        lastError = lastStderrLine(stderr) || "push failed";
        if (attempt < MAX_ATTEMPTS) {
          await this.sleep(BACKOFF_MS);
        }
      }
    }
    throw new SourcePushError(lastError);
  }
}