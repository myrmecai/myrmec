// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Workspace contracts (design section 7.1): acquisition and step scoping are
 * separate operations. A checkout cannot be mistaken for a step-scoped
 * tool root. `GitWorkspaceManager` fetches and checks out the exact
 * assigned `sourceBaseCommit` - never resolving the branch again.
 */
import type { ResolvedSource } from "../orchestration/types.js";

export interface CheckoutHandle {
  workspaceId: string;
  generation: number;
  checkoutPath: string;
  sourceBranch: string;
  targetBranch: string;
  baseCommit: string;
}

export interface StepWorkspace {
  checkoutPath: string;
  workingPath: string;
  sourceSubPath: string;
  sourceBranch: string;
  targetBranch: string;
}

export interface WorkspaceManager {
  /**
   * Acquire a checkout for the resolved source. An orchestration dispatch
   * (unified session execution design, section 9) passes the task-scoped
   * layout so the checkout lives at `<root>/tasks/<dispatchId>/checkout`
   * - acquired at dispatch, released before the terminal/pause frame;
   * without it the manager picks its own unique layout.
   */
  acquire(
    source: ResolvedSource,
    signal?: AbortSignal,
    taskLayout?: { dispatchId: string },
  ): Promise<CheckoutHandle>;
  release(checkout: CheckoutHandle): Promise<void>;
}

export interface WorkspaceScope {
  resolve(checkout: CheckoutHandle, sourceSubPath: string): StepWorkspace;
}