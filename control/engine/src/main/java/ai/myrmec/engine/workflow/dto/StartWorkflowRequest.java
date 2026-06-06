package ai.myrmec.engine.workflow.dto;

import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

/**
 * Request to start a workflow execution.
 */
public record StartWorkflowRequest(
        @NotNull UUID workflowId,
        Map<String, Object> input
) {}
