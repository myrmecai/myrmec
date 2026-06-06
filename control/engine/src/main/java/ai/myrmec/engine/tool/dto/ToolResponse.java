package ai.myrmec.engine.tool.dto;

import ai.myrmec.engine.tool.ToolStatus;
import ai.myrmec.engine.tool.ToolType;

import java.time.Instant;
import java.util.Map;

public record ToolResponse(
        String code,
        String name,
        String description,
        ToolType toolType,
        Map<String, Object> configSchema,
        String docsUrl,
        boolean isSystem,
        ToolStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}
