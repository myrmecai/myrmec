// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.workflow;

import java.util.Map;
import java.util.UUID;

/**
 * Everything a workflow dispatch needs after the host has taken the offered
 * slot (protocol &sect;7.3/&sect;8.1): assembled once by
 * {@link TaskDispatcherService} and parked in {@link PendingTaskDispatches}
 * until the session is ACTIVE.
 *
 * <p>Two families ride the same handshake:</p>
 * <ul>
 *   <li><b>Ordinary INFERENCE</b> ({@code orchestration == false}) &mdash; the
 *       &sect;8.1 input block (transcript, tool policy, output config) is
 *       rebuilt at continuation time (the transcript is composed against the
 *       allocator-owned session id, which only exists after the offer);
 *       {@code session.open} carries only the &sect;6.1 context.</li>
 *   <li><b>ORCHESTRATOR</b> ({@code orchestration == true}) &mdash; the
 *       &sect;16.2 canonical assignment is installed at {@code session.open}
 *       and {@code execution.start} references it by
 *       {@code dispatchId/attemptId/assignmentDigest} only. The assignment
 *       never rides the execution frame, preserving the stored bytes and their
 *       digest exactly as the durable dispatch row recorded them.</li>
 * </ul>
 *
 * @param requestId   the workflow request (the &sect;16.2 runId and the
 *                    allocator refId); also the orchestration session's
 *                    {@code requestId}
 * @param attemptId   the engine-authored attempt == the &sect;16.2 dispatchId
 * @param stepIndex   the step's ordinal, shipped as the response sequence
 * @param continuation the &sect;17.4 typed continuation for a resume attempt
 *                    (null for fresh attempts)
 */
public record TaskDispatchContext(
        UUID taskId,
        UUID attemptId,
        UUID requestId,
        UUID projectId,
        UUID agentProfileId,
        String stepId,
        boolean orchestration,
        Map<String, Object> assignment,
        String assignmentDigest,
        int stepIndex,
        int timeoutSeconds,
        OrchestrationAssignmentAssembler.ContinuationDirective continuation) {
}
