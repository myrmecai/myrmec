package ai.myrmec.engine.tool.dto;

import ai.myrmec.engine.tool.ToolStatus;
import ai.myrmec.engine.tool.ToolType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record UpdateToolRequest(
        @NotBlank(message = "Name is required")
        @Size(min = 1, max = 100, message = "Name must be between 1 and 100 characters")
        String name,

        String description,

        @NotNull(message = "Tool type is required")
        ToolType toolType,

        Map<String, Object> configSchema,

        @Size(max = 500, message = "Docs URL must not exceed 500 characters")
        String docsUrl,

        @NotNull(message = "Status is required")
        ToolStatus status
) {
}
