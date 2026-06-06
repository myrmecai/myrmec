package ai.myrmec.engine.model.dto;

import ai.myrmec.engine.model.ModelStatus;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * Request DTO for updating a model.
 * Code, provider, deploymentType, and modelId cannot be changed.
 */
@Data
public class UpdateModelRequest {

    @Size(max = 200, message = "Model name cannot exceed 200 characters")
    private String name;

    @Size(max = 500, message = "API endpoint cannot exceed 500 characters")
    private String apiEndpoint;

    @Size(max = 500, message = "API key cannot exceed 500 characters")
    private String apiKey;

    private Boolean requiresAuth;

    private Map<String, Object> infraConfig;

    private Map<String, Object> defaultParams;

    private ModelStatus status;
}
