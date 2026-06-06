package ai.myrmec.engine.model.dto;

import ai.myrmec.engine.model.*;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

/**
 * Response DTO for model data.
 * Note: apiKey is never included in responses.
 */
@Data
@Builder
public class ModelResponse {

    private String code;
    private String name;
    private String provider;
    private String providerName;
    private DeploymentType deploymentType;
    private String modelId;
    private String apiEndpoint;
    private boolean requiresAuth;
    private Map<String, Object> infraConfig;
    private Map<String, Object> defaultParams;
    private ModelStatus status;
    private HealthStatus healthStatus;
    private Instant lastHealthCheck;
    private Instant lastTestedAt;
    private String lastTestStatus;
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * Convert entity to response DTO.
     * API key is never exposed.
     */
    public static ModelResponse from(Model model) {
        var providerConfig = model.getProviderConfig();
        return ModelResponse.builder()
                .code(model.getCode())
                .name(model.getName())
                .provider(model.getProvider())
                .providerName(providerConfig != null ? providerConfig.getName() : model.getProvider())
                .deploymentType(model.getDeploymentType())
                .modelId(model.getModelId())
                .apiEndpoint(model.getApiEndpoint())
                .requiresAuth(model.isRequiresAuth())
                .infraConfig(model.getInfraConfig())
                .defaultParams(model.getDefaultParams())
                .status(model.getStatus())
                .healthStatus(model.getHealthStatus())
                .lastHealthCheck(model.getLastHealthCheck())
                .lastTestedAt(model.getLastTestedAt())
                .lastTestStatus(model.getLastTestStatus())
                .createdAt(model.getCreatedAt())
                .updatedAt(model.getUpdatedAt())
                .build();
    }

    /**
     * Convert entity to response DTO without sensitive information.
     * Used for non-admin endpoints.
     */
    public static ModelResponse fromPublic(Model model) {
        var providerConfig = model.getProviderConfig();
        return ModelResponse.builder()
                .code(model.getCode())
                .name(model.getName())
                .provider(model.getProvider())
                .providerName(providerConfig != null ? providerConfig.getName() : model.getProvider())
                .deploymentType(model.getDeploymentType())
                .modelId(model.getModelId())
                .status(model.getStatus())
                .healthStatus(model.getHealthStatus())
                .defaultParams(model.getDefaultParams())
                .build();
    }
}
