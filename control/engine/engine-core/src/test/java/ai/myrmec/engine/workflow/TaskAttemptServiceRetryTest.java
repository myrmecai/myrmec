package ai.myrmec.engine.workflow;

import ai.myrmec.engine.agent.Agent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 10 #71 &mdash; verifies the rate-limit retry path:
 * {@code MODEL_RATE_LIMITED} flips the task back to {@code PENDING}
 * with a {@code nextEligibleAt} respecting the agent-supplied
 * {@code retryAfterSeconds}; other failures leave the existing
 * non-retry behaviour intact; once retries are exhausted the task is
 * marked {@code FAILED} and progression is invoked.
 */
@ExtendWith(MockitoExtension.class)
class TaskAttemptServiceRetryTest {

    @Mock private TaskAttemptRepository taskAttemptRepository;
    @Mock private WorkflowTaskRepository workflowTaskRepository;
    @Mock private ExecutionEventRepository executionEventRepository;
    @Mock private WorkflowProgressionService workflowProgressionService;
    @Mock private TaskMetricsAggregator taskMetricsAggregator;

    @InjectMocks private TaskAttemptService service;

    private WorkflowTask task;
    private TaskAttempt attempt;
    private UUID attemptId;

    @BeforeEach
    void setUp() {
        task = new WorkflowTask();
        task.setId(UUID.randomUUID());
        task.setStatus(TaskStatus.RUNNING);
        task.setAttempt(1);
        task.setStartedAt(Instant.now().minusSeconds(5));
        Agent instance = new Agent();
        instance.setId(UUID.randomUUID());
        task.setAgentInstance(instance);

        attempt = new TaskAttempt();
        attemptId = UUID.randomUUID();
        attempt.setId(attemptId);
        attempt.setTask(task);
        attempt.setAttemptNumber(1);
        attempt.setStatus(AttemptStatus.RUNNING);

        lenient().when(taskAttemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
        lenient().when(taskAttemptRepository.save(any(TaskAttempt.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(workflowTaskRepository.save(any(WorkflowTask.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void rateLimitedFailureSchedulesRetryAndDoesNotFailWorkflow() {
        when(taskAttemptRepository.countRetryableAttempts(task.getId())).thenReturn(0);

        Instant before = Instant.now();
        service.completeFailed(attemptId, "429 from openai",
                TaskAttemptService.ERROR_CODE_RATE_LIMITED, 12);
        Instant after = Instant.now();

        ArgumentCaptor<WorkflowTask> saved = ArgumentCaptor.forClass(WorkflowTask.class);
        verify(workflowTaskRepository).save(saved.capture());
        WorkflowTask persisted = saved.getValue();
        assertThat(persisted.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(persisted.getResult()).isNull();
        assertThat(persisted.getStartedAt()).isNull();
        assertThat(persisted.getAgentInstance()).isNull();
        assertThat(persisted.getAttempt()).isEqualTo(2);
        assertThat(persisted.getNextEligibleAt())
                .isAfterOrEqualTo(before.plusSeconds(12))
                .isBeforeOrEqualTo(after.plusSeconds(13));

        verify(workflowProgressionService, never()).onTaskFailed(any());
    }

    @Test
    void rateLimitedFailureWithNullRetryAfterUsesDefaultBackoff() {
        when(taskAttemptRepository.countRetryableAttempts(task.getId())).thenReturn(0);

        Instant before = Instant.now();
        service.completeFailed(attemptId, "429", TaskAttemptService.ERROR_CODE_RATE_LIMITED, null);
        Instant after = Instant.now();

        ArgumentCaptor<WorkflowTask> saved = ArgumentCaptor.forClass(WorkflowTask.class);
        verify(workflowTaskRepository).save(saved.capture());
        WorkflowTask persisted = saved.getValue();
        assertThat(persisted.getNextEligibleAt())
                .isAfterOrEqualTo(before.plusSeconds(TaskAttemptService.DEFAULT_RATE_LIMIT_BACKOFF_SECONDS))
                .isBeforeOrEqualTo(after.plusSeconds(TaskAttemptService.DEFAULT_RATE_LIMIT_BACKOFF_SECONDS + 1));
    }

    @Test
    void rateLimitedFailureClampsAbsurdRetryAfter() {
        when(taskAttemptRepository.countRetryableAttempts(task.getId())).thenReturn(0);

        Instant before = Instant.now();
        service.completeFailed(attemptId, "429",
                TaskAttemptService.ERROR_CODE_RATE_LIMITED, 9_999_999);

        ArgumentCaptor<WorkflowTask> saved = ArgumentCaptor.forClass(WorkflowTask.class);
        verify(workflowTaskRepository).save(saved.capture());
        WorkflowTask persisted = saved.getValue();
        assertThat(persisted.getNextEligibleAt())
                .isBeforeOrEqualTo(before.plusSeconds(TaskAttemptService.MAX_RATE_LIMIT_BACKOFF_SECONDS + 1));
    }

    @Test
    void rateLimitedFailureFailsWorkflowOnceRetriesExhausted() {
        when(taskAttemptRepository.countRetryableAttempts(task.getId()))
                .thenReturn(TaskAttemptService.MAX_RATE_LIMIT_RETRIES);

        service.completeFailed(attemptId, "429",
                TaskAttemptService.ERROR_CODE_RATE_LIMITED, 5);

        ArgumentCaptor<WorkflowTask> saved = ArgumentCaptor.forClass(WorkflowTask.class);
        verify(workflowTaskRepository).save(saved.capture());
        WorkflowTask persisted = saved.getValue();
        assertThat(persisted.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(persisted.getResult()).isEqualTo(TaskResult.FAILURE);
        assertThat(persisted.getNextEligibleAt()).isNull();

        verify(workflowProgressionService).onTaskFailed(persisted);
    }

    @Test
    void nonRateLimitFailureKeepsExistingBehaviour() {
        // For any other errorCode, the task is left with its previous
        // (RUNNING) status \u2014 the legacy code path. Workflow progression
        // is NOT invoked here; that flow stays where it was before #71.
        service.completeFailed(attemptId, "boom", "SOMETHING_ELSE", null);

        ArgumentCaptor<WorkflowTask> saved = ArgumentCaptor.forClass(WorkflowTask.class);
        verify(workflowTaskRepository).save(saved.capture());
        WorkflowTask persisted = saved.getValue();
        assertThat(persisted.getStatus()).isEqualTo(TaskStatus.RUNNING);
        assertThat(persisted.getNextEligibleAt()).isNull();
        verify(workflowProgressionService, never()).onTaskFailed(any());
    }
}
