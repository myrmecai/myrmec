package ai.myrmec.engine.workflow;

import ai.myrmec.engine.model.Model;
import ai.myrmec.engine.model.ModelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TaskMetricsAggregator}.
 *
 * <p>Covers the two paths the engine cares about most: aggregating a stream of
 * per-call TOKEN_USAGE / TOOL_RESULT events into a metrics map, and folding in
 * model pricing to compute cost. Repositories are mocked — there's no DB or
 * Spring context here so the failure mode is always a logic regression rather
 * than infrastructure flake.
 */
@ExtendWith(MockitoExtension.class)
class TaskMetricsAggregatorTest {

    @Mock private ExecutionEventRepository eventRepository;
    @Mock private WorkflowTaskRepository workflowTaskRepository;
    @Mock private ModelRepository modelRepository;

    @InjectMocks private TaskMetricsAggregator aggregator;

    private UUID attemptId;
    private WorkflowTask task;

    @BeforeEach
    void setUp() {
        attemptId = UUID.randomUUID();
        task = new WorkflowTask();
        task.setId(UUID.randomUUID());
    }

    @Test
    void aggregatesTokensTimingAndCallCounts() {
        List<ExecutionEvent> events = List.of(
                tokenUsage("gpt-4o", 100L, 50L, 150L, 200L),
                tokenUsage("gpt-4o", 60L, 40L, 100L, 150L),
                toolResult(80L),
                toolResult(120L)
        );
        when(eventRepository.findByAttemptIdOrderByCreatedAtAsc(attemptId)).thenReturn(events);
        when(modelRepository.findAll()).thenReturn(List.of());

        Map<String, Object> metrics = aggregator.aggregate(task, attemptId);

        assertThat(metrics)
                .containsEntry("model", "gpt-4o")
                .containsEntry("modelCallCount", 2L)
                .containsEntry("toolCallCount", 2L)
                .containsEntry("promptTokens", 160L)
                .containsEntry("completionTokens", 90L)
                .containsEntry("totalTokens", 250L)
                .containsEntry("modelDurationMs", 350L)
                .containsEntry("toolDurationMs", 200L);
        // No pricing registered → no cost.
        assertThat(metrics).doesNotContainKeys("costUsd", "currency");
    }

    @Test
    void computesCostFromModelPricing() {
        when(eventRepository.findByAttemptIdOrderByCreatedAtAsc(attemptId))
                .thenReturn(List.of(tokenUsage("gpt-4o", 1000L, 500L, 1500L, 100L)));

        Model gpt4o = new Model();
        gpt4o.setModelId("gpt-4o");
        gpt4o.setInputPricePer1kTokens(new BigDecimal("0.005000")); // $5 / 1M
        gpt4o.setOutputPricePer1kTokens(new BigDecimal("0.015000")); // $15 / 1M
        gpt4o.setCurrency("USD");
        when(modelRepository.findAll()).thenReturn(List.of(gpt4o));

        Map<String, Object> metrics = aggregator.aggregate(task, attemptId);

        // 1000 prompt * 0.005/1000 = 0.005000
        // 500 completion * 0.015/1000 = 0.007500
        // total = 0.012500
        assertThat(metrics).containsEntry("currency", "USD");
        assertThat((BigDecimal) metrics.get("costUsd"))
                .isEqualByComparingTo(new BigDecimal("0.012500"));
    }

    @Test
    void fallsBackToAgentSummaryWhenNoPerCallEvents() {
        Map<String, Object> summary = Map.of(
                "model", "claude-3-5-sonnet",
                "promptTokens", 200L,
                "completionTokens", 100L,
                "totalTokens", 300L
        );
        when(eventRepository.findByAttemptIdOrderByCreatedAtAsc(attemptId))
                .thenReturn(List.of(taskMetrics(summary)));
        when(modelRepository.findAll()).thenReturn(List.of());

        Map<String, Object> metrics = aggregator.aggregate(task, attemptId);

        assertThat(metrics)
                .containsEntry("model", "claude-3-5-sonnet")
                .containsEntry("promptTokens", 200L)
                .containsEntry("completionTokens", 100L)
                .containsEntry("totalTokens", 300L);
    }

    @Test
    void persistsMetricsOnTask() {
        when(eventRepository.findByAttemptIdOrderByCreatedAtAsc(attemptId))
                .thenReturn(List.of(tokenUsage("gpt-4o", 10L, 5L, 15L, 50L)));
        when(modelRepository.findAll()).thenReturn(List.of());
        when(workflowTaskRepository.save(any(WorkflowTask.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = aggregator.aggregateAndPersist(task, attemptId);

        assertThat(result).containsEntry("totalTokens", 15L);
        assertThat(task.getMetrics()).isSameAs(result);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private ExecutionEvent tokenUsage(String model, long prompt, long completion, long total, long durationMs) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("model", model);
        data.put("promptTokens", prompt);
        data.put("completionTokens", completion);
        data.put("totalTokens", total);
        ExecutionEvent e = new ExecutionEvent();
        e.setEventType(EventType.TOKEN_USAGE);
        e.setData(data);
        e.setDurationMs(durationMs);
        return e;
    }

    private ExecutionEvent toolResult(long durationMs) {
        ExecutionEvent e = new ExecutionEvent();
        e.setEventType(EventType.TOOL_RESULT);
        e.setDurationMs(durationMs);
        return e;
    }

    private ExecutionEvent taskMetrics(Map<String, Object> data) {
        ExecutionEvent e = new ExecutionEvent();
        e.setEventType(EventType.TASK_METRICS);
        e.setData(data);
        return e;
    }
}
