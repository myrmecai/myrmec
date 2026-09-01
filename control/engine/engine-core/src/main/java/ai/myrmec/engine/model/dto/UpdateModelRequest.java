package ai.myrmec.engine.model.dto;

import ai.myrmec.engine.model.ModelStatus;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * Request DTO for updating a model.
 * Code, provider, and modelId cannot be changed.
 */
@Data
public class UpdateModelRequest {

    @Size(max = 200, message = "Model name cannot exceed 200 characters")
    private String name;

    @Size(max = 500, message = "API endpoint cannot exceed 500 characters")
    private String apiEndpoint;

    private Boolean supportsVision;

    private Map<String, Object> infraConfig;

    private Map<String, Object> defaultParams;

    private ModelStatus status;

    private java.math.BigDecimal inputPrice;
    private java.math.BigDecimal outputPrice;
    @Size(max = 3)
    private String currency;
}
