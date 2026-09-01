package ai.myrmec.engine.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * Request DTO for creating a model.
 */
@Data
public class CreateModelRequest {

    @NotBlank(message = "Model code is required")
    @Size(max = 50, message = "Model code cannot exceed 50 characters")
    @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "Model code can only contain letters, numbers, hyphens, and underscores")
    private String code;

    @NotBlank(message = "Model name is required")
    @Size(max = 200, message = "Model name cannot exceed 200 characters")
    private String name;

    @NotBlank(message = "Provider is required")
    @Size(max = 50, message = "Provider code cannot exceed 50 characters")
    private String provider;

    @NotBlank(message = "Model ID is required")
    @Size(max = 100, message = "Model ID cannot exceed 100 characters")
    private String modelId;

    @Size(max = 500, message = "API endpoint cannot exceed 500 characters")
    private String apiEndpoint;

    private Boolean supportsVision;

    private Map<String, Object> infraConfig;

    private Map<String, Object> defaultParams;

    private java.math.BigDecimal inputPrice;
    private java.math.BigDecimal outputPrice;
    @Size(max = 3)
    private String currency;
}
