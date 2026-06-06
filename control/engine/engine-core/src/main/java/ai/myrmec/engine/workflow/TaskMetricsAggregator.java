package ai.myrmec.engine.workflow;

import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.model.Model;
import ai.myrmec.engine.model.ModelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Aggregates {@link ExecutionEvent}s for a task attempt into a single metrics map
 * (tokens, timing breakdown, cost) and writes it to {@link WorkflowTask#getMetrics()}.
 *
 * <p>Authoritative cost source: looked up against {@code models.input_price_per_1k_tokens} /
 * {@code output_price_per_1k_tokens}. If the model is not found or pricing is missing,
 * {@code costUsd} is omitted.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskMetricsAggregator {

    private static final BigDecimal THOUSAND = new BigDecimal(1000);

    private final ExecutionEventRepository eventRepository;
    private final WorkflowTaskRepository workflowTaskRepository;
    private final ModelRepository modelRepository;

    /**
     * Build and persist the metrics map for the given attempt.
     * Safe to call multiple times; later calls overwrite earlier metrics.
     *
     * @param task      the task whose attempt just finished
     * @param attemptId the finished attempt
     * @return the persisted metrics map (never null, may be empty)
     */
    @Transactional
    public Map<String, Object> aggregateAndPersist(WorkflowTask task, UUID attemptId) {
        Map<String, Object> metrics = aggregate(task, attemptId);
        task.setMetrics(metrics);
        workflowTaskRepository.save(task);
        return metrics;
    }

    /**
     * Compute the metrics map without persisting.
     */
    public Map<String, Object> aggregate(WorkflowTask task, UUID attemptId) {
        List<ExecutionEvent> events = attemptId != null
                ? eventRepository.findByAttemptIdOrderByCreatedAtAsc(attemptId)
                : eventRepository.findByTaskIdOrderByCreatedAtAsc(task.getId());

        long promptTokens = 0;
        long completionTokens = 0;
        long totalTokens = 0;
        long modelCallCount = 0;
        long toolCallCount = 0;
        long modelDurationMs = 0;
        long toolDurationMs = 0;
        String primaryModel = null;
        Map<String, Object> agentSummary = null;

        for (ExecutionEvent event : events) {
            EventType type = event.getEventType();
            if (type == EventType.TOKEN_USAGE) {
                modelCallCount++;
                if (event.getDurationMs() != null) {
                    modelDurationMs += event.getDurationMs();
                }
                Map<String, Object> data = event.getData();
                if (data != null) {
                    promptTokens += asLong(data.get("promptTokens"));
                    completionTokens += asLong(data.get("completionTokens"));
                    totalTokens += asLong(data.get("totalTokens"));
                    if (primaryModel == null && data.get("model") instanceof String s && !s.isBlank()) {
                        primaryModel = s;
                    }
                }
            } else if (type == EventType.TOOL_RESULT) {
                toolCallCount++;
                if (event.getDurationMs() != null) {
                    toolDurationMs += event.getDurationMs();
                }
            } else if (type == EventType.TASK_METRICS) {
                agentSummary = event.getData();
            }
        }

        // Fall back to agent-reported totalTokens if no per-call events arrived.
        if (totalTokens == 0 && agentSummary != null) {
            totalTokens = asLong(agentSummary.get("totalTokens"));
            promptTokens = asLong(agentSummary.get("promptTokens"));
            completionTokens = asLong(agentSummary.get("completionTokens"));
            if (primaryModel == null && agentSummary.get("model") instanceof String s && !s.isBlank()) {
                primaryModel = s;
            }
        }

        if (totalTokens == 0) {
            totalTokens = promptTokens + completionTokens;
        }

        // Wall-clock duration of the attempt.
        Long totalDurationMs = computeAttemptDuration(task, attemptId);

        // Resolve model + pricing.
        if (primaryModel == null) {
            primaryModel = defaultModelId(task);
        }
        final String resolvedModel = primaryModel;
        Optional<Model> pricingModel = resolvedModel != null
                ? modelRepository.findAll().stream()
                    .filter(m -> resolvedModel.equalsIgnoreCase(m.getModelId()))
                    .findFirst()
                : Optional.empty();

        Map<String, Object> metrics = new LinkedHashMap<>();
        if (primaryModel != null) metrics.put("model", primaryModel);
        if (modelCallCount > 0) metrics.put("modelCallCount", modelCallCount);
        if (toolCallCount > 0) metrics.put("toolCallCount", toolCallCount);
        if (promptTokens > 0) metrics.put("promptTokens", promptTokens);
        if (completionTokens > 0) metrics.put("completionTokens", completionTokens);
        if (totalTokens > 0) metrics.put("totalTokens", totalTokens);
        if (modelDurationMs > 0) metrics.put("modelDurationMs", modelDurationMs);
        if (toolDurationMs > 0) metrics.put("toolDurationMs", toolDurationMs);
        if (totalDurationMs != null) metrics.put("totalDurationMs", totalDurationMs);

        final long promptTotal = promptTokens;
        final long completionTotal = completionTokens;

        pricingModel.ifPresent(model -> {
            BigDecimal cost = computeCost(model, promptTotal, completionTotal);
            if (cost != null) {
                metrics.put("costUsd", cost);
                metrics.put("currency", model.getCurrency() != null ? model.getCurrency() : "USD");
            }
        });

        return metrics;
    }

    private Long computeAttemptDuration(WorkflowTask task, UUID attemptId) {
        if (task.getAttempts() != null) {
            for (TaskAttempt a : task.getAttempts()) {
                if (a.getId().equals(attemptId)) {
                    if (a.getStartedAt() != null && a.getCompletedAt() != null) {
                        return Duration.between(a.getStartedAt(), a.getCompletedAt()).toMillis();
                    }
                    if (a.getStartedAt() != null) {
                        return Duration.between(a.getStartedAt(), Instant.now()).toMillis();
                    }
                }
            }
        }
        if (task.getStartedAt() != null && task.getCompletedAt() != null) {
            return Duration.between(task.getStartedAt(), task.getCompletedAt()).toMillis();
        }
        return null;
    }

    private String defaultModelId(WorkflowTask task) {
        AgentProfile profile = task.getAgentProfile();
        if (profile == null) return null;
        try {
            String code = profile.getDefaultModel();
            if (code == null || code.isBlank()) return null;
            return modelRepository.findById(code).map(Model::getModelId).orElse(null);
        } catch (Exception e) {
            // Lazy init issues, etc. — non-fatal.
            return null;
        }
    }

    private BigDecimal computeCost(Model model, long promptTokens, long completionTokens) {
        BigDecimal inputPrice = model.getInputPricePer1kTokens();
        BigDecimal outputPrice = model.getOutputPricePer1kTokens();
        if (inputPrice == null && outputPrice == null) {
            return null;
        }
        BigDecimal cost = BigDecimal.ZERO;
        if (inputPrice != null && promptTokens > 0) {
            cost = cost.add(inputPrice.multiply(BigDecimal.valueOf(promptTokens))
                    .divide(THOUSAND, 6, RoundingMode.HALF_UP));
        }
        if (outputPrice != null && completionTokens > 0) {
            cost = cost.add(outputPrice.multiply(BigDecimal.valueOf(completionTokens))
                    .divide(THOUSAND, 6, RoundingMode.HALF_UP));
        }
        return cost.setScale(6, RoundingMode.HALF_UP);
    }

    private static long asLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignore) {
                return 0;
            }
        }
        return 0;
    }
}
