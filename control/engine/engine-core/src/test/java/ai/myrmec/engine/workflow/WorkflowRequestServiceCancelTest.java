// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.workflow;

import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentProfileRepository;
import ai.myrmec.engine.websocket.AgentWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WFE-04 — verifies the cancel cascade in
 * {@link WorkflowRequestService#cancel(UUID)}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>PENDING request + PENDING task → both CANCELLED.</li>
 *   <li>RUNNING request + RUNNING task → request CANCELLED, task CANCELLED,
 *       attempt ABANDONED, {@code cancelTask(...)} sent to the agent.</li>
 *   <li>RUNNING request + PAUSED task → request CANCELLED, task CANCELLED.</li>
 *   <li>Already-finished request → IllegalStateException.</li>
 *   <li>Double-cancel → IllegalStateException (idempotency guard).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class WorkflowRequestServiceCancelTest {

    @Mock private WorkflowRequestRepository requestRepository;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowTaskRepository taskRepository;
    @Mock private AgentProfileRepository agentProfileRepository;
    @Mock private ai.myrmec.engine.user.UserRepository userRepository;
    @Mock private ai.myrmec.engine.spi.quota.QuotaPolicyEngine quotaPolicyEngine;
    @Mock private AgentWebSocketHandler webSocketHandler;
    @Mock private TaskAttemptRepository taskAttemptRepository;

    @InjectMocks private WorkflowRequestService service;

    private WorkflowRequest request;
    private UUID requestId;

    @BeforeEach
    void setUp() {
        requestId = UUID.randomUUID();
        request = new WorkflowRequest();
        request.setId(requestId);
        request.setStatus(RequestStatus.RUNNING);

        // toResponse() calls request.getWorkflow().getId()/getName() and
        // request.getCreatedBy().getId()/getEmail(), so set minimal stubs.
        Workflow workflow = new Workflow();
        workflow.setId(UUID.randomUUID());
        workflow.setName("test-workflow");
        request.setWorkflow(workflow);

        ai.myrmec.engine.user.User createdBy = new ai.myrmec.engine.user.User();
        createdBy.setId(UUID.randomUUID());
        createdBy.setEmail("test@e2e-test.local");
        request.setCreatedBy(createdBy);
    }

    private WorkflowTask createTask(TaskStatus status) {
        WorkflowTask task = new WorkflowTask();
        task.setId(UUID.randomUUID());
        task.setStatus(status);
        task.setRequest(request);
        return task;
    }

    @Test
    void cancelPendingRequestCancelsPendingTask() {
        request.setStatus(RequestStatus.PENDING);
        WorkflowTask task = createTask(TaskStatus.PENDING);

        when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));
        when(requestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(taskRepository.findByRequestId(requestId)).thenReturn(List.of(task));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = service.cancel(requestId);

        assertThat(response.status()).isEqualTo(RequestStatus.CANCELLED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(task.getCompletedAt()).isNotNull();
        verify(webSocketHandler, never()).cancelTask(any(), any(), any());
    }

    @Test
    void cancelRunningRequestCancelsRunningTaskAndSendsTaskCancel() {
        WorkflowTask task = createTask(TaskStatus.RUNNING);
        Agent agentInstance = new Agent();
        agentInstance.setId(UUID.randomUUID());
        task.setAgentInstance(agentInstance);

        TaskAttempt attempt = new TaskAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setStatus(AttemptStatus.RUNNING);

        when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));
        when(requestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(taskRepository.findByRequestId(requestId)).thenReturn(List.of(task));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(taskAttemptRepository.findFirstByTaskIdOrderByAttemptNumberDesc(task.getId()))
                .thenReturn(Optional.of(attempt));
        when(taskAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.cancel(requestId);

        assertThat(request.getStatus()).isEqualTo(RequestStatus.CANCELLED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.ABANDONED);

        // Verify cancelTask was sent with the correct agent instance and task ID.
        ArgumentCaptor<UUID> agentCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<UUID> taskCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(webSocketHandler).cancelTask(agentCaptor.capture(), taskCaptor.capture(), reasonCaptor.capture());
        assertThat(agentCaptor.getValue()).isEqualTo(agentInstance.getId());
        assertThat(taskCaptor.getValue()).isEqualTo(task.getId());
        assertThat(reasonCaptor.getValue()).contains("cancelled");
    }

    @Test
    void cancelRunningRequestCancelsPausedTask() {
        WorkflowTask task = createTask(TaskStatus.PAUSED);

        when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));
        when(requestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(taskRepository.findByRequestId(requestId)).thenReturn(List.of(task));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.cancel(requestId);

        assertThat(request.getStatus()).isEqualTo(RequestStatus.CANCELLED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        // No agent to notify for a PAUSED task.
        verify(webSocketHandler, never()).cancelTask(any(), any(), any());
    }

    @Test
    void cancelCompletedRequestThrows() {
        request.setStatus(RequestStatus.COMPLETED);
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.cancel(requestId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot cancel a finished request");
    }

    @Test
    void cancelFailedRequestThrows() {
        request.setStatus(RequestStatus.FAILED);
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.cancel(requestId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot cancel a finished request");
    }

    @Test
    void doubleCancelIsRejected() {
        // First cancel succeeds.
        request.setStatus(RequestStatus.RUNNING);
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));
        when(requestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(taskRepository.findByRequestId(requestId)).thenReturn(new ArrayList<>());

        service.cancel(requestId);

        // Second cancel — request is now CANCELLED.
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.cancel(requestId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot cancel a finished request");
    }
}