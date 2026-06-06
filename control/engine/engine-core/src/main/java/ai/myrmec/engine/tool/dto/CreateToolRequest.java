package ai.myrmec.engine.tool.dto;

import ai.myrmec.engine.tool.ToolType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record CreateToolRequest(
        @NotBlank(message = "Code is required")
        @Size(min = 2, max = 50, message = "Code must be between 2 and 50 characters")
        @Pattern(regexp = "^[a-z][a-z0-9_-]*$", message = "Code must start with a letter and contain only lowercase letters, numbers, underscores, and hyphens")
        String code,

        @NotBlank(message = "Name is required")
        @Size(min = 1, max = 100, message = "Name must be between 1 and 100 characters")
        String name,

        String description,

        @NotNull(message = "Tool type is required")
        ToolType toolType,

        Map<String, Object> configSchema,

        @Size(max = 500, message = "Docs URL must not exceed 500 characters")
        String docsUrl
) {
}
