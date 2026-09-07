// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * WorkspaceInspector (design §8.7): computes changed files, clean-tree
 * state, and the candidate-tree hash WITHOUT modifying the real Git
 * index — a temporary index seeded from HEAD, staging only the step's
 * sourceSubPath, then `git write-tree`.
 */
import { execFile } from "node:child_process";
import { existsSync, mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import type { StepWorkspace } from "../workspace/WorkspaceManager.js";

const exec = promisify(execFile);

export interface CandidateTree {
  treeHash: string;
  changedFiles: string[];
  clean: boolean;
}

export interface WorkspaceInspector {
  inspect(workspace: StepWorkspace): Promise<CandidateTree>;
}

/** The concrete Git implementation using a temporary index (§8.7). */
export class GitWorkspaceInspector implements WorkspaceInspector {
  async inspect(workspace: StepWorkspace): Promise<CandidateTree> {
    const tmpIndex = mkdtempSync(path.join(tmpdir(), "ws-idx-"));
    const indexPath = path.join(tmpIndex, "index");
    try {
      // Seed the temp index from HEAD without touching the real index.
      await exec(
        "git",
        ["read-tree", "HEAD", `--index-output=${indexPath}`],
        { cwd: workspace.checkoutPath, windowsHide: true },
      );
      // Stage ONLY the step scope into the temp index — when the scope
      // exists at all (a step may not have created any file yet).
      const scopePath = path.join(workspace.checkoutPath, workspace.sourceSubPath);
      if (existsSync(scopePath)) {
        await exec(
          "git",
          ["add", "--ignore-errors", "-A", "--", workspace.sourceSubPath],
          {
            cwd: workspace.checkoutPath,
            env: { ...process.env, GIT_INDEX_FILE: indexPath },
            windowsHide: true,
          },
        );
      }

      const { stdout: treeOut } = await exec(
        "git",
        ["write-tree"],
        {
          cwd: workspace.checkoutPath,
          env: { ...process.env, GIT_INDEX_FILE: indexPath },
          windowsHide: true,
        },
      );
      const treeHash = treeOut.trim();

      // Changed files within the scope: diff HEAD against the temp tree.
      const { stdout: diffOut } = await exec(
        "git",
        ["diff-tree", "--no-commit-id", "--name-status", "-r", "HEAD", treeHash],
        { cwd: workspace.checkoutPath, windowsHide: true },
      );
      const changedFiles = diffOut
        .split("\n")
        .map((line) => line.trim())
        .filter((line) => line.length > 0)
        .map((line) => {
          // name-status: "A\t<file>", "M\t<file>", "D\t<file>"
          const [status, file] = line.split("\t");
          return `${status}:${file}`;
        });

      return {
        treeHash,
        changedFiles,
        clean: changedFiles.length === 0,
      };
    } finally {
      rmSync(tmpIndex, { recursive: true, force: true });
    }
  }
}