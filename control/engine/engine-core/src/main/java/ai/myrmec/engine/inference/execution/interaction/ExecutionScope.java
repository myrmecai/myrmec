// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import java.util.UUID;

/**
 * Plan section 4: the COMPLETE execution ownership chain every user-API
 * call must validate before any host lookup. A mismatch anywhere fails
 * 404 RESOURCE_NOT_FOUND.
 *
 * @param projectId   the project the workflow lives in
 * @param workflowId  the workflow the request belongs to
 * @param requestId   the workflow request
 * @param taskId      the task inside the request
 * @param attemptId   the task attempt the execution serves
 * @param executionId the session_executions row (§3.1)
 */
public record ExecutionScope(UUID projectId, UUID workflowId, UUID requestId,
                             UUID taskId, UUID attemptId, UUID executionId) {
}