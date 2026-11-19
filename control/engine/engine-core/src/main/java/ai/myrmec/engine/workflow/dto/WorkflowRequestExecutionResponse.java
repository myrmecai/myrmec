// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow.dto;

import ai.myrmec.engine.workflow.TaskStatus;

import java.util.UUID;

/**
 * Plugin helper: resolve a workflow request (run) to the active task/attempt/execution
 * chain so the VS Code extension can call §22 interaction endpoints without walking the
 * full chain itself. Empty when the run has no active execution yet.
 */
public record WorkflowRequestExecutionResponse(
        UUID teamId,
        UUID workflowId,
        UUID requestId,
        UUID taskId,
        UUID attemptId,
        UUID executionId,
        String stepId,
        TaskStatus taskStatus,
        String executionState,
        String holdState) {
}
