package ai.myrmec.engine.tool.dto;

import ai.myrmec.engine.tool.RiskClass;
import ai.myrmec.engine.tool.ToolStatus;
import ai.myrmec.engine.tool.ToolType;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ToolResponse(
        String code,
        String name,
        String description,
        ToolType toolType,
        Map<String, Object> configSchema,
        String docsUrl,
        @JsonProperty("isSystem") boolean isSystem,
        ToolStatus status,
        RiskClass riskClass,
        Instant createdAt,
        Instant updatedAt,
        // Phase 9f — tool description approval state
        String descriptionHash,
        Instant descriptionApprovedAt,
        UUID descriptionApprovedBy
) {
}
