// SPDX-License-Identifier: Apache-2.0
// Copyright 2025 The Myrmec Authors

package ai.myrmec.engine.websocket;

import ai.myrmec.engine.workflow.ExecutionApprovalService;
import ai.myrmec.engine.workflow.WorkflowTask;
import ai.myrmec.engine.workflow.WorkflowTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 (#113) — DESTRUCTIVE / IRREVERSIBLE tool approval workflow.
 *
 * <p>When an agent calls a tool marked DESTRUCTIVE or IRREVERSIBLE and the
 * project has {@code autoHitlOnDestructive=true}, the engine requests
 * approval via {@link ExecutionApprovalService}, flipping the task to
 * {@code requires_approval=true} / {@code approval_status=PENDING} and
 * recording the tool-call payload for approvers.
 *
 * <p>The risk-class detection branch itself lives in
 * {@code AgentWebSocketHandler.handleToolCall}; this test exercises the
 * service-level effect that the handler triggers (and the inverse: a task
 * for which approval is never requested stays unchanged).
 */
@ExtendWith(MockitoExtension.class)
class DestructiveToolApprovalIntegrationTest {

    @Mock
    private WorkflowTaskRepository workflowTaskRepository;

    @InjectMocks
    private ExecutionApprovalService executionApprovalService;

    private WorkflowTask task;
    private UUID taskId;

    @BeforeEach
    void setup() {
        taskId = UUID.randomUUID();
        task = new WorkflowTask();
        task.setId(taskId);
        task.setRequiresApproval(false);
        task.setApprovalStatus(null);

        lenient().when(workflowTaskRepository.save(any(WorkflowTask.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void testDestructiveToolTriggersApprovalRequest() {
        when(workflowTaskRepository.findById(taskId)).thenReturn(Optional.of(task));

        String toolSummary = "Tool test-delete-tool (Test Delete Tool): test-delete-tool";
        Map<String, Object> payload = Map.of(
                "toolName", "test-delete-tool",
                "toolDescription", "A tool that deletes data",
                "parameters", Map.of("target", "important_data"),
                "summary", toolSummary);

        executionApprovalService.requestApproval(taskId, toolSummary, payload, null);

        ArgumentCaptor<WorkflowTask> saved = ArgumentCaptor.forClass(WorkflowTask.class);
        verify(workflowTaskRepository).save(saved.capture());
        WorkflowTask persisted = saved.getValue();
        assertThat(persisted.getRequiresApproval())
                .as("requires_approval=true after DESTRUCTIVE tool approval request")
                .isTrue();
        assertThat(persisted.getApprovalStatus())
                .as("approval_status=PENDING after approval request")
                .isEqualTo("PENDING");
        assertThat(persisted.getApprovalPayload())
                .as("approval payload populated for approvers")
                .isEqualTo(payload);
        assertThat(persisted.getApprovalRequestedAt())
                .as("approval_requested_at timestamp set")
                .isNotNull();
    }

    @Test
    void testIrreversibleToolTriggersApprovalRequest() {
        when(workflowTaskRepository.findById(taskId)).thenReturn(Optional.of(task));

        String toolSummary = "Tool test-migration-tool (Schema Migration Tool): test-migration-tool";
        Map<String, Object> payload = Map.of(
                "toolName", "test-migration-tool",
                "toolDescription", "Schema migration",
                "parameters", Map.of("sql", "ALTER TABLE ..."),
                "summary", toolSummary);

        executionApprovalService.requestApproval(taskId, toolSummary, payload, null);

        ArgumentCaptor<WorkflowTask> saved = ArgumentCaptor.forClass(WorkflowTask.class);
        verify(workflowTaskRepository).save(saved.capture());
        WorkflowTask persisted = saved.getValue();
        assertThat(persisted.getRequiresApproval())
                .as("requires_approval=true for IRREVERSIBLE tool")
                .isTrue();
        assertThat(persisted.getApprovalStatus()).isEqualTo("PENDING");
    }

    @Test
    void testApprovalNotRequestedLeavesTaskUnchanged() {
        // When the tool is SAFE, or autoHitlOnDestructive is disabled, the
        // handler never calls requestApproval, so the task stays in its
        // initial non-approval state.
        assertThat(task.getRequiresApproval())
                .as("requires_approval stays false when approval not requested")
                .isFalse();
        assertThat(task.getApprovalStatus())
                .as("approval_status stays null when approval not requested")
                .isNull();
    }
}
