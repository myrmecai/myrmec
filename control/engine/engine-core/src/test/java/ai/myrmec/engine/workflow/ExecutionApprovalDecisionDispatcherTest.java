// SPDX-License-Identifier: Apache-2.0
// Copyright 2025 The Myrmec Authors

package ai.myrmec.engine.workflow;

import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ExecutionApprovalDecisionDispatcher.
 */
@ExtendWith(MockitoExtension.class)
class ExecutionApprovalDecisionDispatcherTest {

    @Mock
    private ExecutionApprovalService executionApprovalService;

    @Mock
    private WorkflowTaskRepository workflowTaskRepository;

    @Mock
    private WorkflowTask mockTask;

    private ExecutionApprovalDecisionDispatcher dispatcher;
    private UUID taskId;

    @BeforeEach
    void setup() {
        dispatcher = new ExecutionApprovalDecisionDispatcher(
                executionApprovalService,
                workflowTaskRepository
        );
        taskId = UUID.randomUUID();
    }

    @Test
    void testDispatchApproved() {
        // Setup
        when(workflowTaskRepository.findById(taskId)).thenReturn(Optional.of(mockTask));

        // Act
        dispatcher.dispatch(taskId, "APPROVED");

        // Assert
        verify(executionApprovalService).approve(taskId);
        verify(executionApprovalService, never()).reject(any());
        verify(executionApprovalService, never()).expire(any());
    }

    @Test
    void testDispatchRejected() {
        // Setup
        when(workflowTaskRepository.findById(taskId)).thenReturn(Optional.of(mockTask));

        // Act
        dispatcher.dispatch(taskId, "REJECTED");

        // Assert
        verify(executionApprovalService).reject(taskId);
        verify(executionApprovalService, never()).approve(any());
        verify(executionApprovalService, never()).expire(any());
    }

    @Test
    void testDispatchExpired() {
        // Setup
        when(workflowTaskRepository.findById(taskId)).thenReturn(Optional.of(mockTask));

        // Act
        dispatcher.dispatch(taskId, "EXPIRED");

        // Assert
        verify(executionApprovalService).expire(taskId);
        verify(executionApprovalService, never()).approve(any());
        verify(executionApprovalService, never()).reject(any());
    }

    @Test
    void testDispatchTaskNotFound() {
        // Setup
        when(workflowTaskRepository.findById(taskId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(ResourceNotFoundException.class, () -> 
                dispatcher.dispatch(taskId, "APPROVED")
        );
        verify(executionApprovalService, never()).approve(any());
    }

    @Test
    void testDispatchInvalidDecision() {
        // Setup
        when(workflowTaskRepository.findById(taskId)).thenReturn(Optional.of(mockTask));

        // Act & Assert
        assertThrows(IllegalArgumentException.class, () -> 
                dispatcher.dispatch(taskId, "INVALID")
        );
        verify(executionApprovalService, never()).approve(any());
        verify(executionApprovalService, never()).reject(any());
    }

    @Test
    void testDispatchNullDecision() {
        // Act & Assert
        assertThrows(IllegalArgumentException.class, () -> 
                dispatcher.dispatch(taskId, null)
        );
        verify(workflowTaskRepository, never()).findById(any());
    }

    @Test
    void testDispatchBlankDecision() {
        // Act & Assert
        assertThrows(IllegalArgumentException.class, () -> 
                dispatcher.dispatch(taskId, "   ")
        );
        verify(workflowTaskRepository, never()).findById(any());
    }

    @Test
    void testDispatchCaseInsensitive() {
        // Setup
        when(workflowTaskRepository.findById(taskId)).thenReturn(Optional.of(mockTask));

        // Act
        dispatcher.dispatch(taskId, "approved");

        // Assert
        verify(executionApprovalService).approve(taskId);
    }
}
