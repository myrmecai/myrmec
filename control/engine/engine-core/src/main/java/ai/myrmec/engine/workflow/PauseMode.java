// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.workflow;

/**
 * Step-level pause gate configuration.
 *
 * <p>Controls whether the engine pauses a task before dispatching it
 * to the agent, after the agent completes it, both, or neither.</p>
 */
public enum PauseMode {
    /** Normal execution — no pause (default). */
    NONE,
    /** Pause before dispatching the task to the agent. */
    BEFORE,
    /** Pause after the agent reports completion, before creating downstream tasks. */
    AFTER,
    /** Pause both before dispatch and after completion. */
    BOTH
}