// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.workflow;

import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ExecutionApprovalService} — the execution-source half
 * of the unified My Work approvals queue (#113).
 *
 * <p>{@code requestApproval} is exercised by
 * {@code DestructiveToolApprovalIntegrationTest}; this class covers the
 * decision lifecycle: {@code approve} / {@code reject} / {@code expire},
 * the {@code PENDING} guard that makes non-pending decisions no-ops, the
 * expiry timestamp pass-through, and the not-found path.
 */
@ExtendWith(MockitoExtension.class)
class ExecutionApprovalServiceTest {

    @Mock
    private WorkflowTaskRepository taskRepository;

    @InjectMocks
    private ExecutionApprovalService service;

    private UUID taskId;
    private WorkflowTask task;

    @BeforeEach
    void setup() {
        taskId = UUID.randomUUID();
        task = new WorkflowTask();
        task.setId(taskId);
        task.setRequiresApproval(true);
        task.setApprovalStatus("PENDING");

        lenient().when(taskRepository.save(any(WorkflowTask.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void requestApprovalRecordsExpiryAndPayload() {
        WorkflowTask fresh = new WorkflowTask();
        fresh.setId(taskId);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(fresh));

        Instant expiresAt = Instant.now().plusSeconds(3600);
        Map<String, Object> payload = Map.of("toolName", "delete-account");

        WorkflowTask result = service.requestApproval(taskId, "Delete account", payload, expiresAt);

        assertThat(result.getRequiresApproval()).isTrue();
        assertThat(result.getApprovalStatus()).isEqualTo("PENDING");
        assertThat(result.getApprovalPayload()).isEqualTo(payload);
        assertThat(result.getApprovalRequestedAt()).isNotNull();
        assertThat(result.getApprovalExpiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void approveTransitionsPendingToApproved() {
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        WorkflowTask result = service.approve(taskId);

        assertThat(result.getApprovalStatus()).isEqualTo("APPROVED");
        verify(taskRepository).save(task);
    }

    @Test
    void rejectTransitionsPendingToRejected() {
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        WorkflowTask result = service.reject(taskId);

        assertThat(result.getApprovalStatus()).isEqualTo("REJECTED");
        verify(taskRepository).save(task);
    }

    @Test
    void expireTransitionsPendingToExpired() {
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        WorkflowTask result = service.expire(taskId);

        assertThat(result.getApprovalStatus()).isEqualTo("EXPIRED");
        verify(taskRepository).save(task);
    }

    @Test
    void approveOnNonPendingTaskIsNoOp() {
        task.setApprovalStatus("APPROVED");
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        WorkflowTask result = service.approve(taskId);

        assertThat(result.getApprovalStatus()).isEqualTo("APPROVED");
        verify(taskRepository, never()).save(any(WorkflowTask.class));
    }

    @Test
    void rejectOnNonPendingTaskIsNoOp() {
        task.setApprovalStatus("EXPIRED");
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        WorkflowTask result = service.reject(taskId);

        assertThat(result.getApprovalStatus()).isEqualTo("EXPIRED");
        verify(taskRepository, never()).save(any(WorkflowTask.class));
    }

    @Test
    void expireOnNonPendingTaskIsNoOp() {
        task.setApprovalStatus("REJECTED");
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        WorkflowTask result = service.expire(taskId);

        assertThat(result.getApprovalStatus()).isEqualTo("REJECTED");
        verify(taskRepository, never()).save(any(WorkflowTask.class));
    }

    @Test
    void decisionOnMissingTaskThrowsResourceNotFound() {
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(taskId))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
